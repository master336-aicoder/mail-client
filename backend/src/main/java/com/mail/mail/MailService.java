package com.mail.mail;

import com.mail.account.AccountService;
import com.mail.account.domain.MailAccount;
import com.mail.account.domain.MailAccountRepository;
import com.mail.auth.CurrentUser;
import com.mail.common.BizException;
import com.mail.common.MailFlags;
import com.mail.common.MailErrors;
import com.mail.folder.FolderService;
import com.mail.folder.domain.Folder;
import com.mail.folder.domain.FolderRepository;
import com.mail.mail.domain.AttachmentMeta;
import com.mail.mail.domain.AttachmentMetaRepository;
import com.mail.mail.domain.Message;
import com.mail.mail.domain.MessageRepository;
import com.mail.mail.dto.BatchMoveRequest;
import com.mail.mail.dto.ComposeRequest;
import com.mail.mail.dto.MessageListItem;
import com.mail.mail.dto.MessageDetail;
import com.mail.mail.dto.UpdateFlagsRequest;
import com.mail.notification.NotificationService;
import com.mail.notification.domain.Notification;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 邮件核心：列表、详情、发送、草稿、标志、删除。
 */
@Service
@RequiredArgsConstructor
public class MailService {

    private final MessageRepository messageRepository;
    private final AttachmentMetaRepository attachmentMetaRepository;
    private final MailAccountRepository accountRepository;
    private final AccountService accountService;
    private final FolderService folderService;
    private final FolderRepository folderRepository;
    private final SmtpTransport smtpTransport;
    private final NotificationService notificationService;

    private static final java.util.Set<String> DRAFT_NAMES = java.util.Set.of("草稿", "Drafts", "draft", "DRAFT");

    @Transactional(readOnly = true)
    public MessageListItem.Page list(Long folderId, int page, int size, String flag, String q, Long accountId) {
        Long userId = CurrentUser.requireId();
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 200);
        PageRequest pr = PageRequest.of(safePage - 1, safeSize, Sort.by(Sort.Direction.DESC, "date"));
        Folder folder = folderRepository.findById(folderId).orElse(null);
        // 归属校验：拒绝访问不存在或属于他人账户的文件夹，避免越权读取他人邮件列表
        if (folder == null || !userId.equals(folder.getUserId())) {
            throw new BizException(40300, "无权访问");
        }
        // 合并视图：账户级菜单（accountId 为 null）聚合所有账户同名文件夹
        boolean merged = folder.getAccountId() == null;
        // 草稿文件夹判定：支持多语言名称，避免重命名后失效
        boolean isDraft = folder.getName() != null && DRAFT_NAMES.contains(folder.getName());
        String kw = (q == null || q.isBlank()) ? null : q.trim();
        Page<Message> p;
        if (merged) {
            List<Long> ids = folderRepository.findByUserIdAndAccountIdNotNullOrderBySeqAsc(userId)
                    .stream()
                    .filter(f -> f.getName() != null && f.getName().equals(folder.getName()))
                    // B13：合并视图按账户过滤
                    .filter(f -> accountId == null || accountId.equals(f.getAccountId()))
                    .map(Folder::getId)
                    .toList();
            if (ids.isEmpty()) ids = List.of(folderId);
            p = kw == null
                    ? (isDraft
                        ? messageRepository.findByFolderIdInAndDeletedFalseAndDraftTrue(ids, pr)
                        : messageRepository.findByFolderIdInAndDeletedFalseAndDraftFalse(ids, pr))
                    : (isDraft
                        ? messageRepository.searchByFoldersAnyDraft(ids, kw, pr)
                        : messageRepository.searchByFolders(ids, kw, pr));
        } else {
            p = kw == null
                    ? (isDraft
                        ? messageRepository.findByFolderIdAndDeletedFalseAndDraftTrue(folderId, pr)
                        : messageRepository.findByFolderIdAndDeletedFalseAndDraftFalse(folderId, pr))
                    : (isDraft
                        ? messageRepository.searchByFolderAnyDraft(folderId, kw, pr)
                        : messageRepository.searchByFolder(folderId, kw, pr));
        }
        List<MessageListItem> items = p.getContent().stream().map(m -> {
            MessageListItem it = new MessageListItem();
            it.setId(m.getId());
            it.setFolderId(m.getFolderId());
            it.setSubject(m.getSubject());
            it.setSender(m.getSender());
            it.setSenderEmail(m.getSenderEmail());
            it.setDate(m.getDate());
            it.setFlags(m.getFlags());
            it.setHasAttach(m.isHasAttach());
            it.setPreview(m.getPreview());
            it.setSize(m.getSize());
            it.setRead(MailFlags.has(m.getFlags(), MailFlags.READ));
            return it;
        }).toList();
        MessageListItem.Page result = new MessageListItem.Page();
        result.setTotal(p.getTotalElements());
        result.setList(items);
        return result;
    }

    @Transactional(readOnly = true)
    public MessageDetail detail(Long id) {
        Long userId = CurrentUser.requireId();
        Message m = messageRepository.findById(id).orElseThrow(() -> new BizException("邮件不存在"));
        if (!m.getUserId().equals(userId)) throw new BizException(40300, "无权访问");
        MessageDetail d = new MessageDetail();
        d.setId(m.getId());
        d.setSubject(m.getSubject());
        d.setSender(m.getSender());
        d.setSenderEmail(m.getSenderEmail());
        d.setRecipients(m.getRecipients());
        d.setCc(m.getCc());
        d.setDate(m.getDate());
        d.setFlags(m.getFlags());
        d.setRead(MailFlags.has(m.getFlags(), MailFlags.READ));
        d.setStarred(MailFlags.has(m.getFlags(), MailFlags.STARRED));
        d.setImportant(MailFlags.has(m.getFlags(), MailFlags.IMPORTANT));
        d.setBodyHtml(m.getBodyHtml());
        d.setBodyText(m.getBodyText());
        d.setAttachments(attachmentMetaRepository.findByMessageId(id));
        d.setOstRef(m.getRemoteUid());
        d.setIsDraft(m.isDraft());
        d.setAccountId(m.getAccountId());
        d.setBcc(m.getBcc());
        return d;
    }

    @Transactional
    public Long send(ComposeRequest req) {
        Long userId = CurrentUser.requireId();
        MailAccount account = accountRepository.findById(req.getAccountId())
                .orElseThrow(() -> new BizException("账户不存在"));
        if (!account.getUserId().equals(userId)) throw new BizException(40300, "无权操作");
        // 附件归属校验：不允许引用他人附件
        if (req.getAttachments() != null && !req.getAttachments().isEmpty()) {
            validateAttachmentOwnership(userId, req.getAttachments());
        }
        // 先校验收件人：JavaMail 不会拒绝 not-an-email 这类串，会一路发到 SMTP 才失败，
        // 且真实服务器回的是英文码，提前拦下既省一次网络往返，也能给出明确中文原因。
        requireAddresses(req);
        // 实际 SMTP 发送
        String secret = accountService.decryptSecret(account.getId());
        try {
            smtpTransport.send(account, secret, req);
        } catch (Exception e) {
            // 发送失败会回滚当前事务，通知用独立事务落库，否则提醒也一起被回滚掉
            notificationService.notifyDetached(userId, Notification.SEND_FAILED,
                    "发信失败：" + (req.getSubject() == null || req.getSubject().isBlank() ? "(无主题)" : req.getSubject()),
                    "发件账户 " + account.getEmail() + "，收件人 " + String.join("、", req.getTo())
                            + "。原因：" + MailErrors.explain(e),
                    "/settings/accounts", java.time.Duration.ZERO);
            throw e;
        }
        // 写入已发送
        Message m = new Message();
        m.setUserId(userId);
        m.setAccountId(account.getId());
        m.setFolderId(folderService.resolveAccountFolderId(userId, account.getId(), "已发送"));
        m.setSubject(req.getSubject());
        m.setSender(account.getDisplayName());
        m.setSenderEmail(account.getEmail());
        m.setRecipients(String.join(",", req.getTo()));
        m.setCc(String.join(",", req.getCc()));
        m.setBcc(String.join(",", req.getBcc()));
        m.setBodyHtml(req.getBodyHtml());
        m.setBodyText(stripHtml(req.getBodyHtml()));
        m.setDate(LocalDateTime.now());
        m.setSize(req.getBodyHtml() == null ? 0L : (long) req.getBodyHtml().length());
        boolean hasAtt = req.getAttachments() != null && !req.getAttachments().isEmpty();
        m.setHasAttach(hasAtt);
        m.setFlags(MailFlags.set(0, MailFlags.READ, true));
        Long sentId = messageRepository.save(m).getId();
        // 将本次发送引用的附件挂到「已发送」邮件下
        if (hasAtt) {
            List<AttachmentMeta> atts = attachmentMetaRepository.findAllById(req.getAttachments());
            atts.forEach(a -> a.setMessageId(sentId));
            attachmentMetaRepository.saveAll(atts);
        }
        return sentId;
    }

    @Transactional
    public Long saveDraft(ComposeRequest req) {
        Long userId = CurrentUser.requireId();
        MailAccount account = accountRepository.findById(req.getAccountId()).orElse(null);
        if (account == null || !account.getUserId().equals(userId)) {
            throw new BizException(40300, "无权操作该账户");
        }
        if (req.getAttachments() != null && !req.getAttachments().isEmpty()) {
            validateAttachmentOwnership(userId, req.getAttachments());
        }
        Message m = new Message();
        m.setUserId(userId);
        m.setAccountId(req.getAccountId());
        m.setFolderId(folderService.resolveAccountFolderId(userId, req.getAccountId(), "草稿"));
        m.setSubject(req.getSubject());
        m.setRecipients(String.join(",", req.getTo()));
        m.setCc(String.join(",", req.getCc()));
        m.setBcc(String.join(",", req.getBcc()));
        m.setBodyHtml(req.getBodyHtml());
        m.setBodyText(stripHtml(req.getBodyHtml()));
        m.setDate(LocalDateTime.now());
        m.setDraft(true);
        boolean hasAtt = req.getAttachments() != null && !req.getAttachments().isEmpty();
        m.setHasAttach(hasAtt);
        Long draftId = messageRepository.save(m).getId();
        if (hasAtt) {
            List<AttachmentMeta> atts = attachmentMetaRepository.findAllById(req.getAttachments());
            atts.forEach(a -> a.setMessageId(draftId));
            attachmentMetaRepository.saveAll(atts);
        }
        return draftId;
    }

    @Transactional
    public void updateDraft(Long id, ComposeRequest req) {
        Long userId = CurrentUser.requireId();
        Message m = messageRepository.findById(id).orElseThrow(() -> new BizException("草稿不存在"));
        if (!m.getUserId().equals(userId)) throw new BizException(40300, "无权操作");
        if (!m.isDraft()) throw new BizException("该邮件不是草稿");
        MailAccount account = accountRepository.findById(req.getAccountId()).orElse(null);
        if (account == null || !account.getUserId().equals(userId)) {
            throw new BizException(40300, "无权操作该账户");
        }
        if (req.getAttachments() != null && !req.getAttachments().isEmpty()) {
            validateAttachmentOwnership(userId, req.getAttachments());
        }
        m.setAccountId(req.getAccountId());
        m.setSubject(req.getSubject());
        m.setRecipients(String.join(",", req.getTo()));
        m.setCc(String.join(",", req.getCc()));
        m.setBcc(String.join(",", req.getBcc()));
        m.setBodyHtml(req.getBodyHtml());
        m.setBodyText(stripHtml(req.getBodyHtml()));
        m.setDate(LocalDateTime.now());
        boolean hasAtt = req.getAttachments() != null && !req.getAttachments().isEmpty();
        m.setHasAttach(hasAtt);
        messageRepository.save(m);
        // 更新附件关联
        if (hasAtt) {
            List<AttachmentMeta> atts = attachmentMetaRepository.findAllById(req.getAttachments());
            atts.forEach(a -> a.setMessageId(id));
            attachmentMetaRepository.saveAll(atts);
        }
    }

    @Transactional
    public void updateFlags(Long id, UpdateFlagsRequest req) {
        Long userId = CurrentUser.requireId();
        Message m = messageRepository.findById(id).orElseThrow(() -> new BizException("邮件不存在"));
        if (!m.getUserId().equals(userId)) throw new BizException(40300, "无权操作");
        int flags = m.getFlags();
        if (req.getRead() != null) flags = MailFlags.set(flags, MailFlags.READ, req.getRead());
        if (req.getStarred() != null) flags = MailFlags.set(flags, MailFlags.STARRED, req.getStarred());
        if (req.getImportant() != null) flags = MailFlags.set(flags, MailFlags.IMPORTANT, req.getImportant());
        m.setFlags(flags);
        messageRepository.save(m);
    }

    @Transactional
    public void markDeleted(Long id) {
        Long userId = CurrentUser.requireId();
        Message m = messageRepository.findById(id).orElseThrow(() -> new BizException("邮件不存在"));
        if (!m.getUserId().equals(userId)) throw new BizException(40300, "无权操作");
        m.setDeleted(true);
        messageRepository.save(m);
    }

    // ------------------------------------------------------------------ 批量操作

    /** 单次批量上限：与前端分页上限一致，避免一次请求改走上万行。 */
    private static final int MAX_BATCH = 200;

    /** 批量设置已读/星标/重要：只处理属于当前用户的邮件，返回实际改动数。 */
    @Transactional
    public int batchUpdateFlags(Long userId, List<Long> ids, UpdateFlagsRequest req) {
        if (req.getRead() == null && req.getStarred() == null && req.getImportant() == null) {
            throw new BizException("请选择要设置的状态（已读 / 星标 / 重要）");
        }
        List<Message> list = ownMessages(userId, ids);
        for (Message m : list) {
            int flags = m.getFlags();
            if (req.getRead() != null) flags = MailFlags.set(flags, MailFlags.READ, req.getRead());
            if (req.getStarred() != null) flags = MailFlags.set(flags, MailFlags.STARRED, req.getStarred());
            if (req.getImportant() != null) flags = MailFlags.set(flags, MailFlags.IMPORTANT, req.getImportant());
            m.setFlags(flags);
        }
        messageRepository.saveAll(list);
        return list.size();
    }

    /** 批量删除（软删除）：他人邮件与不存在的 id 一并跳过。 */
    @Transactional
    public int batchDelete(Long userId, List<Long> ids) {
        List<Message> list = ownMessages(userId, ids);
        for (Message m : list) m.setDeleted(true);
        messageRepository.saveAll(list);
        return list.size();
    }

    /**
     * 批量移动到目标文件夹。目标必须是某个具体账户下的文件夹：
     * 合并视图（accountId 为空）只是查询入口，邮件落在它上面就再也读不到了。
     * 已在目标文件夹里的邮件不计入结果，避免前端把"没动"当成"动了"。
     */
    @Transactional
    public int batchMove(Long userId, BatchMoveRequest req) {
        Folder target = ownFolder(userId, req.getFolderId());
        if (target.getAccountId() == null) {
            throw new BizException("不能移动到「全部账户」视图，请选择具体账户下的文件夹");
        }
        List<Message> list = ownMessages(userId, req.getIds());
        List<Message> moved = new java.util.ArrayList<>();
        for (Message m : list) {
            if (target.getId().equals(m.getFolderId())) continue;
            m.setFolderId(target.getId());
            moved.add(m);
        }
        if (!moved.isEmpty()) messageRepository.saveAll(moved);
        return moved.size();
    }

    /** 整个文件夹标记已读。 */
    @Transactional
    public int markFolderRead(Long folderId) {        Long userId = CurrentUser.requireId();
        Folder folder = ownFolder(userId, folderId);
        return messageRepository.markFoldersRead(userId, targetFolderIds(userId, folder));
    }

    /**
     * 清空文件夹：账户级菜单是合并视图，清空时会连带各账户的同名文件夹，
     * 与列表看到的内容保持一致，因此提示里明确写出实际移除数量。
     */
    @Transactional
    public int clearFolder(Long folderId) {
        Long userId = CurrentUser.requireId();
        Folder folder = ownFolder(userId, folderId);
        int removed = messageRepository.softDeleteByFolders(userId, targetFolderIds(userId, folder));
        if (removed > 0) {
            notificationService.notify(userId, Notification.FOLDER_CLEARED,
                    "已清空文件夹「" + folder.getName() + "」",
                    "共移除 " + removed + " 封邮件，该操作不可恢复。",
                    "/mail/folder/" + folderId);
        }
        return removed;
    }

    /** 合并视图（folder.accountId 为空）对应所有账户的同名文件夹，否则只有自身。 */
    private List<Long> targetFolderIds(Long userId, Folder folder) {
        if (folder.getAccountId() != null) return List.of(folder.getId());
        List<Long> ids = folderRepository.findByUserIdAndAccountIdNotNullOrderBySeqAsc(userId).stream()
                .filter(f -> f.getName() != null && f.getName().equals(folder.getName()))
                .map(Folder::getId)
                .toList();
        return ids.isEmpty() ? List.of(folder.getId()) : ids;
    }

    private Folder ownFolder(Long userId, Long folderId) {
        Folder folder = folderRepository.findById(folderId).orElse(null);
        if (folder == null || !userId.equals(folder.getUserId())) throw new BizException(40300, "无权访问");
        return folder;
    }

    private List<Message> ownMessages(Long userId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) throw new BizException("请选择要操作的邮件");
        if (ids.size() > MAX_BATCH) {
            throw new BizException("单次最多操作 " + MAX_BATCH + " 封邮件，请分页选择");
        }
        List<Message> list = messageRepository.findAllById(ids).stream()
                .filter(m -> userId.equals(m.getUserId()))
                .toList();
        if (list.isEmpty()) throw new BizException("没有可操作的邮件");
        return list;
    }

    private void validateAttachmentOwnership(Long userId, List<Long> attachmentIds) {
        List<AttachmentMeta> atts = attachmentMetaRepository.findAllById(attachmentIds);
        for (AttachmentMeta a : atts) {
            if (!userId.equals(a.getUserId())) {
                throw new BizException(40300, "无权使用他人附件");
            }
        }
    }

    /** 简单邮箱式校验：local@domain.tld，不接受空格、逗号、分号（前端逐个录入，每项一个地址）。 */
    private static final java.util.regex.Pattern ADDRESS =
            java.util.regex.Pattern.compile("[^@\\s,;]+@[^@\\s,;]+\\.[^@\\s,;]+");

    /** 发送前校验收信人：空或格式非法都当场拒绝，并给出可直接照做的中文原因。 */
    private static void requireAddresses(ComposeRequest req) {
        if (!hasAddress(req.getTo()) && !hasAddress(req.getCc()) && !hasAddress(req.getBcc())) {
            throw new BizException(40002, "请至少填写一个收件人");
        }
        checkAddresses(req.getTo(), "收件人");
        checkAddresses(req.getCc(), "抄送");
        checkAddresses(req.getBcc(), "密送");
    }

    private static boolean hasAddress(List<String> list) {
        return list != null && list.stream().anyMatch(s -> s != null && !s.isBlank());
    }

    private static void checkAddresses(List<String> list, String label) {
        if (list == null) return;
        for (String a : list) {
            if (a == null || a.isBlank()) continue;
            String v = a.trim();
            if (!ADDRESS.matcher(v).matches()) {
                throw new BizException(40002, label + "地址格式不正确：" + v + "，请填写如 name@example.com 的完整地址");
            }
        }
    }

    private String stripHtml(String html) {
        if (html == null || html.isBlank()) return "";
        return org.jsoup.Jsoup.parse(html).text();
    }
}