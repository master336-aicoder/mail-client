package com.mail.sync;

import com.mail.account.domain.MailAccount;
import com.mail.account.domain.MailAccountRepository;
import com.mail.folder.FolderService;
import com.mail.mail.domain.Message;
import com.mail.mail.domain.MessageRepository;
import com.mail.protocol.MailClient;
import com.mail.protocol.ProtocolFactory;
import com.mail.push.PushService;
import com.mail.rule.RuleEngine;
import com.mail.rule.domain.Rule;
import com.mail.rule.domain.RuleRepository;
import com.mail.mail.domain.AttachmentMeta;
import com.mail.mail.domain.AttachmentMetaRepository;
import com.mail.mail.domain.PendingInlinePart;
import com.mail.storage.AttachmentStorage;
import com.mail.storage.StorageRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 账户级同步：拉取、去重、规则、推送。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountSync {

    private final MailAccountRepository accountRepository;
    private final MessageRepository messageRepository;
    private final ProtocolFactory protocolFactory;
    private final FolderService folderService;
    private final RuleEngine ruleEngine;
    private final RuleRepository ruleRepository;
    private final PushService pushService;
    private final com.mail.account.SettingsService settingsService;
    private final AttachmentMetaRepository attachmentMetaRepository;
    private final StorageRouter storageRouter;

    /** 正在同步的账户集合：防止定时轮询与手动同步对同一账户并发全量拉取。 */
    private final Set<Long> syncingAccounts = ConcurrentHashMap.newKeySet();

    /**
     * 同步所有启用账户。
     */
    public void syncEnabledAccounts() {
        List<MailAccount> accounts = accountRepository.findByActiveTrue();
        for (MailAccount acc : accounts) {
            try {
                sync(acc);
            } catch (Exception e) {
                log.warn("账户 {} 同步失败: {}", acc.getEmail(), e.getMessage());
            }
        }
    }

    /**
     * 按收取频率同步：仅同步已到期的启用账户。默认间隔读取该账户所属用户的设置。
     */
    public void syncEnabledAccountsDue() {
        List<MailAccount> accounts = accountRepository.findByActiveTrue();
        LocalDateTime now = LocalDateTime.now();
        for (MailAccount acc : accounts) {
            try {
                int defaultInterval = settingsService.defaultSyncIntervalSec(acc.getUserId());
                int intervalSec = acc.getSyncIntervalSec() != null
                        ? acc.getSyncIntervalSec() : defaultInterval;
                // 未同步过或已达到间隔 → 同步
                if (acc.getLastSyncedAt() == null
                        || acc.getLastSyncedAt().plusSeconds(Math.max(15, intervalSec)).isBefore(now)) {
                    sync(acc);
                }
            } catch (Exception e) {
                log.warn("账户 {} 同步失败: {}", acc.getEmail(), e.getMessage());
            }
        }
    }

    public void sync(MailAccount account) {
        Long key = account.getId();
        if (key != null && !syncingAccounts.add(key)) {
            log.info("账户 {} 已有同步在进行，跳过本次", account.getEmail());
            return;
        }
        try {
            doSync(account);
        } finally {
            if (key != null) syncingAccounts.remove(key);
        }
    }

    // 不加 @Transactional：fetchInbox 是网络操作，避免整箱拉取期间长时间持有 DB 事务/连接；
    // 单条消息落库各自成事务即可。
    void doSync(MailAccount account) {
        MailClient client = protocolFactory.clientFor(account.getProtocol());
        List<Message> fetched = client.fetchInbox(account);
        // 规则以账户为基准：仅应用属于该账户的启用规则
        List<Rule> rules = ruleRepository
                .findByUserIdAndAccountIdAndEnabledTrueOrderByPriorityAsc(account.getUserId(), account.getId());
        // 确保当前账户的默认文件夹存在，并定位该账户的收件箱
        folderService.ensureAccountDefaults(account.getUserId(), account.getId());
        Long inboxFolderId = folderService.resolveAccountFolderId(account.getUserId(), account.getId(), "收件箱");
        int inserted = 0;
        for (Message msg : fetched) {
            msg.setFolderId(inboxFolderId);
            try {
                Optional<Message> existing = messageRepository
                        .findByAccountIdAndFolderIdAndRemoteUid(account.getId(), msg.getFolderId(), msg.getRemoteUid());
                if (existing.isPresent()) {
                    // 已存在：仅当本地正文缺失而从服务器成功解析到正文时，回填正文
                    Message ex = existing.get();
                    boolean bodyMissing = (ex.getBodyHtml() == null || ex.getBodyHtml().isBlank())
                            && (ex.getBodyText() == null || ex.getBodyText().isBlank());
                    boolean restored = (msg.getBodyHtml() != null && !msg.getBodyHtml().isBlank())
                            || (msg.getBodyText() != null && !msg.getBodyText().isBlank());
                    if (bodyMissing && restored) {
                        ex.setBodyHtml(msg.getBodyHtml());
                        ex.setBodyText(msg.getBodyText());
                        ex.setSize(msg.getSize());
                        messageRepository.save(ex);
                    }
                    continue;
                }
                messageRepository.save(msg);
                persistInline(account, msg);
                boolean hit = ruleEngine.apply(account.getUserId(), msg, rules);
                if (hit) {
                    messageRepository.save(msg);
                    pushService.publish(account.getUserId(), "RULE_TRIGGERED",
                            java.util.Map.of("messageId", msg.getId(), "subject", msg.getSubject()));
                }
                pushService.publish(account.getUserId(), "MAIL_NEW",
                        java.util.Map.of("messageId", msg.getId(), "accountId", account.getId(),
                                "folderId", msg.getFolderId(), "subject", msg.getSubject()));
                inserted++;
            } catch (Exception e) {
                // 单条消息处理失败不影响其他消息与整个同步的状态推进
                log.warn("同步消息失败 {}/{} uid={}: {}", account.getEmail(),
                        msg.getRemoteUid(), e.getMessage());
            }
        }
        if (inserted > 0) {
            log.info("账户 {} 新增 {} 封邮件", account.getEmail(), inserted);
        }
        account.setLastSyncedAt(LocalDateTime.now());
        accountRepository.save(account);
    }

    /**
     * 将消息中携带的内嵌图片持久化为附件元数据，并把正文中的 `cid:X`
     * 引用改写为内嵌资源读取地址（带 access_token）。
     */
    private void persistInline(MailAccount account, Message msg) {
        if (msg.getPendingInline() == null || msg.getPendingInline().isEmpty()) return;
        if (!msg.getBodyHtml().contains("cid:")) {
            msg.getPendingInline().clear();
            return;
        }
        AttachmentStorage storage = storageRouter.defaultStorage();
        // 先持久化所有内嵌资源，拿到附件 id
        List<AttachmentMeta> metas = new java.util.ArrayList<>();
        for (PendingInlinePart part : msg.getPendingInline()) {
            try {
                String key = account.getUserId() + "/inline/" + msg.getId() + "/"
                        + System.currentTimeMillis() + "-" + Math.abs(part.contentId.hashCode());
                String ref = storage.save(key, new ByteArrayInputStream(part.data), part.data.length, part.mimeType);
                AttachmentMeta meta = new AttachmentMeta();
                meta.setUserId(account.getUserId());
                meta.setMessageId(msg.getId());
                meta.setAccountId(account.getId());
                meta.setName(part.fileName == null ? "inline-image" : part.fileName);
                meta.setMimeType(part.mimeType);
                meta.setSize((long) part.data.length);
                meta.setStorageRef(ref);
                meta.setStorageType(storage.type());
                meta.setContentId(part.contentId);
                meta.setDisposition("inline");
                metas.add(attachmentMetaRepository.save(meta));
            } catch (Exception e) {
                log.warn("持久化内嵌资源失败 {} cid={}: {}", account.getEmail(), part.contentId, e.getMessage());
            }
        }
        msg.getPendingInline().clear();
        if (metas.isEmpty()) return;
        // 重写正文 cid:X → /api/inline/{id}（token 由前端在渲染时追加）
        for (AttachmentMeta meta : metas) {
            String cid = meta.getContentId();
            if (cid == null || cid.isBlank()) continue;
            String url = "/api/inline/" + meta.getId();
            msg.setBodyHtml(replaceCid(msg.getBodyHtml(), cid, url));
        }
        messageRepository.save(msg);
    }

    /**
     * 将正文中所有对指定 cid 的引用（src="cid:X"、src=cid:X、url(cid:X) 等）
     * 替换为内嵌资源地址。cid 为唯一 token，直接做字面量替换即可覆盖各上下文。
     */
    private static String replaceCid(String html, String cid, String baseUrl) {
        if (html == null || cid == null || cid.isBlank()) return html;
        return html.replace("cid:" + cid, baseUrl)
                   .replace("CID:" + cid, baseUrl);
    }
}