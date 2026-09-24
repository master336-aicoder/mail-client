package com.mail.account;

import com.mail.account.domain.MailAccount;
import com.mail.account.domain.MailAccountRepository;
import com.mail.account.dto.AccountRequest;
import com.mail.account.dto.ProbeResult;
import com.mail.auth.CurrentUser;
import com.mail.common.BizException;
import com.mail.common.CryptoService;
import com.mail.folder.FolderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * 账户：绑定、探测、增删查。
 */
@Service
@RequiredArgsConstructor
public class AccountService {

    private final MailAccountRepository accountRepository;
    private final CryptoService cryptoService;
    private final FolderService folderService;

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
        MailAccount a = new MailAccount();
        a.setUserId(userId);
        a.setEmail(req.getEmail());
        a.setDisplayName(req.getDisplayName());
        a.setProtocol(req.getProtocol().toUpperCase(Locale.ROOT));
        a.setHost(req.getHost());
        a.setPort(req.getPort());
        a.setEncryption(req.getEncryption());
        a.setAuthType(req.getAuthType());
        a.setUsername(req.getUsername() == null ? req.getEmail() : req.getUsername());
        if (req.getSecret() != null && !req.getSecret().isBlank()) {
            a.setSecretEncrypted(cryptoService.encrypt(req.getSecret()));
        }
        a.setSmtpHost(req.getSmtpHost());
        a.setSmtpPort(req.getSmtpPort());
        a.setSmtpEncryption(req.getSmtpEncryption());
        MailAccount saved = accountRepository.save(a);
        // 首次绑定确保合并视图与账户级默认文件夹
        folderService.ensureDefaults(userId);
        folderService.ensureAccountDefaults(userId, saved.getId());
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
        accountRepository.delete(a);
    }

    /**
     * 更新账户的同步频率（以及 active 状态），用于账户设置里的「收取频率」。
     */
    @Transactional
    public MailAccount updateSync(Long id, Integer syncIntervalSec, Boolean active) {
        Long userId = CurrentUser.requireId();
        MailAccount a = accountRepository.findById(id).orElseThrow(() -> new BizException("账户不存在"));
        if (!a.getUserId().equals(userId)) throw new BizException(40300, "无权操作");
        if (syncIntervalSec != null) {
            a.setSyncIntervalSec(Math.max(15, Math.min(syncIntervalSec, 24 * 3600)));
        }
        if (active != null) {
            a.setActive(active);
        }
        return accountRepository.save(a);
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