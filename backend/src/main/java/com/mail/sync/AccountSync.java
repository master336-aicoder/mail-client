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
    private final AttachmentMetaRepository attachmentMetaRepository;
    private final StorageRouter storageRouter;
    private final com.mail.notification.NotificationService notificationService;

    /** 正在同步的账户集合：防止定时轮询与手动同步对同一账户并发全量拉取。 */
    private final Set<Long> syncingAccounts = ConcurrentHashMap.newKeySet();

    public void sync(MailAccount account) {
        sync(account, null);
    }

    /**
     * 同步单个账户；{@code progress} 非空时按真实阶段上报进度与说明。
     */
    public void sync(MailAccount account, SyncProgress progress) {
        Long key = account.getId();
        if (key != null && !syncingAccounts.add(key)) {
            log.info("账户 {} 已有同步在进行，跳过本次", account.getEmail());
            step(progress, 100, "该账户已有同步在进行，本次跳过");
            return;
        }
        try {
            doSync(account, progress);
        } finally {
            if (key != null) syncingAccounts.remove(key);
        }
    }

    private static void step(SyncProgress progress, int percent, String message) {
        if (progress != null) progress.step(percent, message);
    }

    // 不加 @Transactional：fetchInbox 是网络操作，避免整箱拉取期间长时间持有 DB 事务/连接；
    // 单条消息落库各自成事务即可。
    void doSync(MailAccount account, SyncProgress progress) {
        MailClient client = protocolFactory.clientFor(account.getProtocol());
        // 规则以账户为基准：仅应用属于该账户的启用规则
        List<Rule> rules = ruleRepository
                .findByUserIdAndAccountIdAndEnabledTrueOrderByPriorityAsc(account.getUserId(), account.getId());
        // 确保当前账户的默认文件夹存在，并定位该账户的收件箱
        folderService.ensureAccountDefaults(account.getUserId(), account.getId());
        Long inboxFolderId = folderService.resolveAccountFolderId(account.getUserId(), account.getId(), "收件箱");
        // IMAP 走序号增量：仅拉取本地高水位之后的新邮件，稳态下不再逐封拉正文。
        // POP3 消息编号会因下载/删除重排，不适用，退回全量（sinceSeq=0）。
        int sinceSeq = 0;
        if ("imap".equalsIgnoreCase(account.getProtocol())) {
            sinceSeq = (int) messageRepository.maxRemoteSeq(account.getId(), inboxFolderId);
        }
        step(progress, 5, "正在连接 " + account.getHost() + " 收取 " + account.getEmail());

        SyncStat st;
        boolean imap = "imap".equalsIgnoreCase(account.getProtocol());
        if (imap) {
            // IMAP 边拉边入库：每解析一封立即落库，慢网关下即使中途断连也不丢已完成的部分。
            st = new SyncStat();
            final Long fId = inboxFolderId;
            int pulled = client.fetchInboxInto(account, sinceSeq,
                    msg -> persistOne(account, rules, msg, fId, true, st));
            st.total = pulled;
            step(progress, 70, "收件箱拉到 " + st.total + " 封，已入库 " + st.inserted + " 封");
        } else {
            List<Message> inbox = client.fetchInbox(account, sinceSeq);
            step(progress, 10, "收件箱拉到 " + inbox.size() + " 封，正在解析入库…");
            st = storeMessages(account, rules, inbox, inboxFolderId, true, 10, 60, progress);
        }
        int inserted = st.inserted;
        int failed = st.failed;
        int total = st.total;

        // 已发送同步（仅 IMAP：POP3 协议无已发送箱）。已发送是附加能力，
        // 单箱拉取失败不阻断收件主流程，仅记日志。
        if (imap) {
            try {
                Long sentFolderId = folderService.resolveAccountFolderId(
                        account.getUserId(), account.getId(), "已发送");
                int sentSince = (int) messageRepository.maxRemoteSeq(account.getId(), sentFolderId);
                SyncStat sentSt = new SyncStat();
                int sentPulled = client.fetchSentInto(account, sentSince,
                        msg -> persistOne(account, rules, msg, sentFolderId, false, sentSt));
                if (sentPulled > 0) {
                    step(progress, 84, "已发送箱拉到 " + sentPulled + " 封，已入库 " + sentSt.inserted + " 封");
                }
            } catch (Exception e) {
                log.warn("账户 {} 已发送同步失败（不影响收件）: {}", account.getEmail(), e.getMessage());
            }
        }
        if (inserted > 0) {
            log.info("账户 {} 新增 {} 封邮件", account.getEmail(), inserted);
            String preview = String.join("、", st.newSubjects) + (inserted > st.newSubjects.size() ? " 等" : "");
            notificationService.notify(account.getUserId(),
                    com.mail.notification.domain.Notification.MAIL_NEW,
                    account.getEmail() + " 收到 " + inserted + " 封新邮件",
                    "最新邮件：" + preview,
                    "/mail/folder/" + inboxFolderId);
        }
        if (failed > 0) {
            notificationService.notifyThrottled(account.getUserId(),
                    com.mail.notification.domain.Notification.RECEIVE_FAILED,
                    account.getEmail() + " 有 " + failed + " 封邮件收取后处理失败",
                    "本轮拉取 " + total + " 封，其中 " + failed + " 封解析或入库失败，可稍后重试同步（详情见服务端日志）",
                    "/mail/folder/" + inboxFolderId, java.time.Duration.ofMinutes(30));
        }
        account.setLastSyncedAt(LocalDateTime.now());
        accountRepository.save(account);
        step(progress, 100, "收取 " + total + " 封，新增 " + inserted + " 封");
    }

    /** 一轮同步中某文件夹的落库统计。 */
    private static final class SyncStat {
        int total;
        int inserted;
        int failed;
        final List<String> newSubjects = new java.util.ArrayList<>();
    }

    /**
     * 把一批拉取到的邮件落库到 {@code folderId}：去重、正文/附件回填、（收件箱）规则与推送。
     *
     * @param applyRules 是否应用规则并推送「新邮件」——仅收件箱为 true；
     *                   已发送等箱只需入库，不应触发收信规则与通知。
     * @param basePct    进度条起始百分比
     * @param spanPct    该批邮件占用的进度百分比跨度
     */
    private SyncStat storeMessages(MailAccount account, List<Rule> rules, List<Message> fetched,
                                   Long folderId, boolean applyRules, int basePct, int spanPct,
                                   SyncProgress progress) {
        SyncStat st = new SyncStat();
        int total = fetched.size();
        st.total = total;
        int done = 0;
        for (Message msg : fetched) {
            done++;
            persistOne(account, rules, msg, folderId, applyRules, st);
            if (total > 0) {
                step(progress, basePct + (int) ((double) spanPct * done / total),
                        "已处理 " + done + "/" + total + " 封，新增 " + st.inserted + " 封");
            }
        }
        return st;
    }

    /**
     * 单封邮件落库：去重（含正文/主题/附件回填）、（收件箱）规则与推送。
     * 统计写入 {@code st}；任何异常仅记日志并计入 failed，不影响同批其他邮件。
     * 供批量 {@link #storeMessages} 与 IMAP 流式回调共用。
     */
    private void persistOne(MailAccount account, List<Rule> rules, Message msg,
                            Long folderId, boolean applyRules, SyncStat st) {
        msg.setFolderId(folderId);
        try {
            Optional<Message> existing = messageRepository
                    .findByAccountIdAndFolderIdAndRemoteUid(account.getId(), msg.getFolderId(), msg.getRemoteUid());
            if (existing.isPresent()) {
                Message ex = existing.get();
                boolean subjectMissing = ex.getSubject() == null || ex.getSubject().isBlank();
                boolean subjectRestored = msg.getSubject() != null && !msg.getSubject().isBlank();
                boolean bodyMissing = (ex.getBodyHtml() == null || ex.getBodyHtml().isBlank())
                        && (ex.getBodyText() == null || ex.getBodyText().isBlank());
                boolean restored = (msg.getBodyHtml() != null && !msg.getBodyHtml().isBlank())
                        || (msg.getBodyText() != null && !msg.getBodyText().isBlank());
                // 旧版本（或看门狗截断）可能留下仅有信封、无主题/正文的空壳行；本次重新解析到
                // 主题或正文时回填，使已入库的空白邮件在下次同步自愈，避免页面显示"无主题、无内容"
                if ((subjectMissing && subjectRestored) || (bodyMissing && restored)) {
                    if (subjectMissing && subjectRestored) ex.setSubject(msg.getSubject());
                    if (bodyMissing && restored) {
                        ex.setBodyHtml(msg.getBodyHtml());
                        ex.setBodyText(msg.getBodyText());
                    }
                    ex.setSize(msg.getSize());
                    messageRepository.save(ex);
                }
                // 旧版本收信未采集普通附件：本次重新解析到附件且库中尚无附件元数据时回填，
                // 使此前已入库、页面不显示附件的邮件在下次同步后补全（POP3 全量扫描/IMAP 窗口内可自愈）
                if (!msg.getPendingAttachments().isEmpty()
                        && !attachmentMetaRepository.existsByMessageIdAndDisposition(ex.getId(), "attachment")) {
                    ex.getPendingAttachments().addAll(msg.getPendingAttachments());
                    msg.getPendingAttachments().clear();
                    persistAttachments(account, ex);
                }
                return;
            }
            messageRepository.save(msg);
            persistInline(account, msg);
            persistAttachments(account, msg);
            if (applyRules) {
                boolean hit = ruleEngine.apply(account.getUserId(), msg, rules);
                if (hit) {
                    messageRepository.save(msg);
                    pushService.publish(account.getUserId(), "RULE_TRIGGERED",
                            java.util.Map.of("messageId", msg.getId(), "subject", msg.getSubject()));
                }
                pushService.publish(account.getUserId(), "MAIL_NEW",
                        java.util.Map.of("messageId", msg.getId(), "accountId", account.getId(),
                                "folderId", msg.getFolderId(), "subject", msg.getSubject()));
            }
            st.inserted++;
            if (applyRules && st.newSubjects.size() < 3) {
                st.newSubjects.add(msg.getSubject() == null || msg.getSubject().isBlank()
                        ? "(无主题)" : msg.getSubject());
            }
        } catch (Exception e) {
            // 单条消息处理失败不影响其他消息与整个同步的状态推进
            st.failed++;
            log.warn("同步消息失败 {} uid={}: {}", account.getEmail(),
                    msg.getRemoteUid(), e.getMessage());
        }
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
                meta.setName(clampName(part.fileName == null ? "inline-image" : part.fileName));
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
     * 将消息携带的普通附件（含 message/rfc822 邮件副本 .eml）持久化为附件元数据，
     * 供前端附件列表展示与下载。内嵌图片走 persistInline，不在此列。
     */
    private void persistAttachments(MailAccount account, Message msg) {
        if (msg.getPendingAttachments() == null || msg.getPendingAttachments().isEmpty()) return;
        AttachmentStorage storage = storageRouter.defaultStorage();
        int persisted = 0;
        for (PendingInlinePart part : msg.getPendingAttachments()) {
            try {
                String key = account.getUserId() + "/attach/" + msg.getId() + "/"
                        + System.currentTimeMillis() + "-" + Math.abs(part.fileName == null ? 0 : part.fileName.hashCode());
                String ref = storage.save(key, new ByteArrayInputStream(part.data), part.data.length, part.mimeType);
                AttachmentMeta meta = new AttachmentMeta();
                meta.setUserId(account.getUserId());
                meta.setMessageId(msg.getId());
                meta.setAccountId(account.getId());
                meta.setName(clampName(part.fileName == null ? "attachment" : part.fileName));
                meta.setMimeType(part.mimeType);
                meta.setSize((long) part.data.length);
                meta.setStorageRef(ref);
                meta.setStorageType(storage.type());
                meta.setDisposition("attachment");
                attachmentMetaRepository.save(meta);
                persisted++;
            } catch (Exception e) {
                log.warn("持久化附件失败 {} name={}: {}", account.getEmail(), part.fileName, e.getMessage());
            }
        }
        msg.getPendingAttachments().clear();
        if (persisted > 0 && !msg.isHasAttach()) {
            msg.setHasAttach(true);
            messageRepository.save(msg);
        }
    }

    /** 附件名列宽 512，按字符截断留 8 位余量，避免超长文件名导致落库失败。 */
    private static String clampName(String name) {
        if (name == null) return "attachment";
        String n = name.trim();
        if (n.isEmpty()) return "attachment";
        return n.length() <= 500 ? n : n.substring(0, 500);
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