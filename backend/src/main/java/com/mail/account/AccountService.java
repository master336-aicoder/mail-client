package com.mail.account;

import com.mail.account.domain.MailAccount;
import com.mail.account.domain.MailAccountRepository;
import com.mail.account.dto.AccountRequest;
import com.mail.account.dto.AccountUpdateRequest;
import com.mail.account.dto.ProbeResult;
import com.mail.auth.CurrentUser;
import com.mail.common.BizException;
import com.mail.common.CryptoService;
import com.mail.folder.FolderService;
import com.mail.mail.SmtpTransport;
import com.mail.notification.NotificationService;
import com.mail.notification.domain.Notification;
import com.mail.protocol.ProtocolFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 账户：绑定、探测、增删查。
 */
@Service
@RequiredArgsConstructor
public class AccountService {

    private final MailAccountRepository accountRepository;
    private final CryptoService cryptoService;
    private final FolderService folderService;
    private final com.mail.common.HostGuard hostGuard;
    private final ProtocolFactory protocolFactory;
    private final SmtpTransport smtpTransport;
    private final com.mail.task.TaskService taskService;
    private final NotificationService notificationService;
    private final com.mail.mail.domain.MessageRepository messageRepository;
    private final com.mail.mail.domain.AttachmentMetaRepository attachmentMetaRepository;
    private final com.mail.storage.StorageRouter storageRouter;

    /**
     * 根据邮箱域名与已知规则探测 IMAP/POP3/SMTP 推荐配置。
     */
    public List<ProbeResult> autoDetect(String email) {
        String domain = email.substring(email.indexOf('@') + 1).toLowerCase(Locale.ROOT);
        ProbeResult r = new ProbeResult();
        r.setProtocol("IMAP");
        r.setHost("imap." + domain);
        r.setPort(993);
        r.setEncryption("SSL");
        r.setSmtpHost("smtp." + domain);
        r.setSmtpPort(587);
        r.setSmtpEncryption("STARTTLS");
        // 常见服务商覆盖
        switch (domain) {
            case "gmail.com", "googlemail.com" -> { r.setHost("imap.gmail.com"); r.setSmtpHost("smtp.gmail.com"); }
            case "qq.com" -> { r.setHost("imap.qq.com"); r.setSmtpHost("smtp.qq.com"); }
            case "163.com", "126.com" -> { r.setHost("imap.163.com"); r.setSmtpHost("smtp.163.com"); }
            case "outlook.com", "hotmail.com", "live.com" -> { r.setHost("outlook.office365.com"); r.setSmtpHost("smtp-mail.outlook.com"); }
            default -> { }
        }
        return List.of(r);
    }

    @Transactional
    public MailAccount create(AccountRequest req) {
        Long userId = CurrentUser.requireId();
        hostGuard.check(req.getHost(), "收件服务器");
        hostGuard.check(req.getSmtpHost(), "发件服务器");
        MailAccount a = new MailAccount();
        a.setUserId(userId);
        a.setEmail(req.getEmail());
        a.setDisplayName(req.getDisplayName());
        a.setProtocol(req.getProtocol().toUpperCase(Locale.ROOT));
        a.setHost(req.getHost());
        a.setPort(req.getPort());
        a.setEncryption(enc(req.getEncryption()));
        a.setAuthType(req.getAuthType());
        a.setUsername(req.getUsername() == null ? req.getEmail() : req.getUsername());
        if (req.getSecret() != null && !req.getSecret().isBlank()) {
            a.setSecretEncrypted(cryptoService.encrypt(req.getSecret()));
        }
        a.setSmtpHost(req.getSmtpHost());
        a.setSmtpPort(req.getSmtpPort());
        a.setSmtpEncryption(enc(req.getSmtpEncryption()));
        a.setIgnoreCertError(Boolean.TRUE.equals(req.getIgnoreCertError()));
        MailAccount saved = accountRepository.save(a);
        // 首次绑定确保合并视图与账户级默认文件夹
        folderService.ensureDefaults(userId);
        folderService.ensureAccountDefaults(userId, saved.getId());
        // 每个账户常驻一条周期收信任务，不在每次收信时新建记录
        taskService.ensureAccountSyncTask(saved);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<MailAccount> list() {
        Long userId = CurrentUser.requireId();
        return accountRepository.findByUserId(userId);
    }

    @Transactional
    public void delete(Long id) {
        Long userId = CurrentUser.requireId();
        MailAccount a = accountRepository.findById(id).orElseThrow(() -> new BizException("账户不存在"));
        if (!a.getUserId().equals(userId)) throw new BizException(40300, "无权操作");
        taskService.removeAccountTasks(id);
        folderService.deleteByAccountId(userId, id);
        accountRepository.delete(a);
        notificationService.notify(userId, Notification.ACCOUNT_DELETED,
                "邮件账户已移除：" + a.getEmail(),
                "该账户的周期收信任务已一并删除，停止收信；如需恢复请重新添加账户并输入密码。",
                "/settings/accounts");
    }

    /**
     * 重置账户：清空该账户下所有文件夹的本地邮件与附件（含存储文件），随后由控制器触发
     * 一次立即同步重新拉取。只动本地数据，绝不删除服务器上的邮件。返回清理统计供前端提示。
     *
     * <p>安全门禁：必须先把账户「停用」再重置。停用会经 {@link com.mail.task.TaskService#ensureAccountSyncTask}
     * 让该账户的周期同步任务自动失效（enabled=false、nextRunAt 清空），从而保证清空与重拉期间
     * 没有后台轮询与本次操作抢同一批数据；也避免误操作正在使用的账户。
     */
    @Transactional
    public Map<String, Object> reset(Long id) {
        Long userId = CurrentUser.requireId();
        MailAccount a = accountRepository.findById(id).orElseThrow(() -> new BizException("账户不存在"));
        if (!a.getUserId().equals(userId)) throw new BizException(40300, "无权操作");
        if (a.isActive()) {
            throw new BizException(40010, "重置前请先停用该账户：停用后其自动同步任务会一并失效，避免清空与后台轮询抢数据。");
        }
        if (taskService.syncRunActive(a)) {
            throw new BizException(40011, "该账户本轮同步尚未结束，请到「后台任务」等待其完成或先中止，再执行重置。");
        }
        // 先尽力删除附件的存储文件（I/O，失败不回滚，仅记日志）
        int files = 0;
        for (var meta : attachmentMetaRepository.findByAccountId(id)) {
            try {
                storageRouter.byType(meta.getStorageType()).delete(meta.getStorageRef());
                files++;
            } catch (Exception e) {
                // 单个文件删除失败不影响其余
            }
        }
        int metas = attachmentMetaRepository.deleteAllByAccountId(id);
        int messages = messageRepository.deleteAllByAccountId(id);
        notificationService.notify(userId, Notification.ACCOUNT_RESET,
                "已重置账户：" + a.getEmail(),
                "清空本地邮件 " + messages + " 封、附件记录 " + metas + " 个，正在重新拉取（账户仍为停用，重拉完成后如需自动收信请手动启用）……",
                "/settings/accounts");
        return Map.of("accountId", id, "messages", messages, "attachments", metas, "files", files);
    }

    /** 重置后触发一次立即同步重新拉取。与 {@link #reset} 分开调用，确保删除事务已提交。 */
    public void triggerResync(Long id) {
        Long userId = CurrentUser.requireId();
        MailAccount a = accountRepository.findById(id).orElseThrow(() -> new BizException("账户不存在"));
        if (!a.getUserId().equals(userId)) throw new BizException(40300, "无权操作");
        taskService.runNow(userId, taskService.ensureAccountSyncTask(a).getId());
    }

    /**
     * 更新账户配置：只覆盖请求中非空字段，收信与发信的服务器/端口/加密方式相互独立。
     * 服务器地址可改，因此每次都要重新做主机校验（SSRF 白名单）。
     */
    @Transactional
    public MailAccount update(Long id, AccountUpdateRequest req) {
        Long userId = CurrentUser.requireId();
        MailAccount a = accountRepository.findById(id).orElseThrow(() -> new BizException("账户不存在"));
        if (!a.getUserId().equals(userId)) throw new BizException(40300, "无权操作");
        if (req.getEmail() != null && !req.getEmail().isBlank()) a.setEmail(req.getEmail());
        if (req.getDisplayName() != null) a.setDisplayName(req.getDisplayName());
        if (req.getProtocol() != null) a.setProtocol(req.getProtocol().toUpperCase(Locale.ROOT));
        if (req.getHost() != null && !req.getHost().isBlank()) {
            hostGuard.check(req.getHost(), "收件服务器");
            a.setHost(req.getHost());
        }
        if (req.getSmtpHost() != null && !req.getSmtpHost().isBlank()) {
            hostGuard.check(req.getSmtpHost(), "发件服务器");
            a.setSmtpHost(req.getSmtpHost());
        }
        if (req.getPort() != null) a.setPort(req.getPort());
        if (req.getSmtpPort() != null) a.setSmtpPort(req.getSmtpPort());
        if (req.getEncryption() != null) a.setEncryption(enc(req.getEncryption()));
        if (req.getSmtpEncryption() != null) a.setSmtpEncryption(enc(req.getSmtpEncryption()));
        if (req.getUsername() != null && !req.getUsername().isBlank()) a.setUsername(req.getUsername());
        if (req.getSecret() != null && !req.getSecret().isBlank()) {
            a.setSecretEncrypted(cryptoService.encrypt(req.getSecret()));
        }
        // 收信排程：间隔上限放宽到 30 天，前端按分钟/小时/天换算成秒
        if (req.getSyncIntervalSec() != null) {
            a.setSyncIntervalSec(Math.max(15, Math.min(req.getSyncIntervalSec(), 30 * 24 * 3600)));
        }
        String mode = req.getSyncMode() == null || req.getSyncMode().isBlank()
                ? a.getSyncMode() : req.getSyncMode().trim().toUpperCase(Locale.ROOT);
        if (MailAccount.SYNC_DAILY.equals(mode)) {
            LocalTime at = req.getSyncDailyTime() == null || req.getSyncDailyTime().isBlank()
                    ? a.getSyncDailyTime() : parseDailyTime(req.getSyncDailyTime());
            if (at == null) throw new BizException("请选择每日收信时刻，格式如 08:30");
            a.setSyncDailyTime(at);
        }
        a.setSyncMode(mode == null ? MailAccount.SYNC_INTERVAL : mode);
        if (req.getActive() != null) a.setActive(req.getActive());
        if (req.getIgnoreCertError() != null) a.setIgnoreCertError(req.getIgnoreCertError());
        MailAccount saved = accountRepository.save(a);
        // 频率/启用状态变了，同步任务的调度配置要跟着走
        taskService.ensureAccountSyncTask(saved);
        return saved;
    }

    /**
     * 用已保存的配置分别测试收信（IMAP/POP3）与发信（SMTP）连通性。
     * 不包事务：全程是网络交互，避免长时间占用数据库连接。
     */
    public Map<String, Object> test(Long id) {
        MailAccount a = own(id);
        if (a.getSecretEncrypted() == null || a.getSecretEncrypted().isBlank()) {
            throw new BizException("该账户还没有保存密码，请先在编辑弹窗中填写密码");
        }
        return runTest(a, cryptoService.decrypt(a.getSecretEncrypted()));
    }

    /**
     * 用表单里尚未保存的配置测试连接，避免「改完先保存才能测」的绕路。
     * {@code accountId} 仅用于在密码留空时复用该账户已存的密码；密码本身不会被返回或写入。
     */
    public Map<String, Object> testConfig(AccountRequest req, Long accountId) {
        Long userId = CurrentUser.requireId();
        hostGuard.check(req.getHost(), "收件服务器");
        hostGuard.check(req.getSmtpHost(), "发件服务器");
        MailAccount a = new MailAccount();
        a.setUserId(userId);
        a.setEmail(req.getEmail());
        a.setDisplayName(req.getDisplayName());
        a.setProtocol(req.getProtocol() == null ? "IMAP" : req.getProtocol().toUpperCase(Locale.ROOT));
        a.setHost(req.getHost());
        a.setPort(req.getPort());
        a.setEncryption(enc(req.getEncryption()));
        a.setAuthType(req.getAuthType());
        a.setUsername(req.getUsername() == null || req.getUsername().isBlank() ? req.getEmail() : req.getUsername());
        a.setSmtpHost(req.getSmtpHost());
        a.setSmtpPort(req.getSmtpPort());
        a.setSmtpEncryption(enc(req.getSmtpEncryption()));
        a.setIgnoreCertError(Boolean.TRUE.equals(req.getIgnoreCertError()));

        String password = req.getSecret();
        if (password == null || password.isBlank()) {
            // 留空表示沿用已存密码：编辑场景下密码不回显，但仍需能测试
            MailAccount saved = accountId == null ? null : own(accountId);
            password = saved == null || saved.getSecretEncrypted() == null || saved.getSecretEncrypted().isBlank()
                    ? null : cryptoService.decrypt(saved.getSecretEncrypted());
        }
        if (password == null || password.isBlank()) {
            throw new BizException("请先填写密码后再测试连接（用户名通常为邮箱全称，部分服务商需填授权码）");
        }
        return runTest(a, password);
    }

    /** 执行收发两侧测试，返回带端点与中文原因的明细，供前端分行展示。 */
    private Map<String, Object> runTest(MailAccount a, String password) {
        String receiveError = protocolFactory.clientFor(a.getProtocol()).testConnection(a, password);
        String sendError = smtpTransport.testConnection(a, password);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("receiveTarget", receiveTarget(a));
        out.put("receiveOk", receiveError == null);
        out.put("receiveError", receiveError);
        out.put("sendTarget", smtpTransport.smtpTarget(a));
        out.put("sendOk", sendError == null);
        out.put("sendError", sendError);
        out.put("allOk", receiveError == null && sendError == null);
        return out;
    }

    private String receiveTarget(MailAccount a) {
        String proto = a.getProtocol() == null ? "IMAP" : a.getProtocol().toUpperCase(Locale.ROOT);
        int port = a.getPort() != null ? a.getPort() : ("POP3".equals(proto) ? 995 : 993);
        return proto + " " + a.getHost() + ":" + port + "（" + encLabel(a.getEncryption()) + "）";
    }

    private static String encLabel(String enc) {
        if ("SSL".equalsIgnoreCase(enc)) return "SSL 隐式 TLS";
        if ("NONE".equalsIgnoreCase(enc)) return "不加密";
        return "STARTTLS";
    }

    /** 入参校验放行小写，落库与列表展示统一大写，避免同一账户出现 ssl / SSL 两种值。 */
    private static String enc(String v) {
        return v == null ? null : v.trim().toUpperCase(Locale.ROOT);
    }

    /** 接受 08:30 / 8:30 / 8:30:00 一类的写法，无法解析时返回 null 由调用方给出中文提示。 */
    private static LocalTime parseDailyTime(String v) {
        String t = v.trim();
        // 补零：8:30 → 08:30
        if (t.length() >= 4 && t.charAt(1) == ':') t = "0" + t;
        try {
            return LocalTime.parse(t);
        } catch (Exception e) {
            return null;
        }
    }

    /** 按 id 取当前用户的账户，越权与不存在统一报错。 */
    private MailAccount own(Long id) {
        Long userId = CurrentUser.requireId();
        MailAccount a = accountRepository.findById(id).orElseThrow(() -> new BizException("账户不存在"));
        if (!a.getUserId().equals(userId)) throw new BizException(40300, "无权操作");
        return a;
    }

    @Transactional(readOnly = true)
    public String decryptSecret(Long id) {
        Long userId = CurrentUser.requireId();
        MailAccount a = accountRepository.findById(id).orElseThrow(() -> new BizException("账户不存在"));
        if (!a.getUserId().equals(userId)) throw new BizException(40300, "无权操作");
        if (a.getSecretEncrypted() == null) return "";
        return cryptoService.decrypt(a.getSecretEncrypted());
    }
}