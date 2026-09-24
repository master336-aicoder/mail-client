package com.mail.mail;

import com.mail.account.AccountService;
import com.mail.account.domain.MailAccount;
import com.mail.account.domain.MailAccountRepository;
import com.mail.auth.CurrentUser;
import com.mail.common.BizException;
import com.mail.common.MailFlags;
import com.mail.folder.FolderService;
import com.mail.folder.domain.Folder;
import com.mail.folder.domain.FolderRepository;
import com.mail.mail.domain.AttachmentMeta;
import com.mail.mail.domain.AttachmentMetaRepository;
import com.mail.mail.domain.Message;
import com.mail.mail.domain.MessageRepository;
import com.mail.mail.dto.ComposeRequest;
import com.mail.mail.dto.MessageListItem;
import com.mail.mail.dto.MessageDetail;
import com.mail.mail.dto.UpdateFlagsRequest;
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

    @Transactional(readOnly = true)
    public MessageListItem.Page list(Long folderId, int page, int size, String flag, String q, Long accountId) {
        Long userId = CurrentUser.requireId();
        PageRequest pr = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "date"));
        Folder folder = folderRepository.findById(folderId).orElse(null);
        // 合并视图：账户级菜单（accountId 为 null）聚合所有账户同名文件夹
        boolean merged = folder != null && folder.getAccountId() == null;
        // 草稿文件夹按 is_draft=true 列表，其余文件夹仅列非草稿
        boolean isDraft = folder != null && folder.getName() != null && folder.getName().equals("草稿");
        String kw = (q == null || q.isBlank()) ? null : q.trim();
        Page<Message> p;
        if (merged) {
            List<Long> ids = folderRepository.findByUserIdAndAccountIdNotNullOrderBySeqAsc(userId)
                    .stream()
                    .filter(f -> f.getName() != null && f.getName().equals(folder.getName()))
                    .map(Folder::getId)
                    .toList();
            if (ids.isEmpty()) ids = List.of(folderId);
            p = kw == null
                    ? (isDraft
                        ? messageRepository.findByFolderIdInAndDeletedFalseAndDraftTrue(ids, pr)
                        : messageRepository.findByFolderIdInAndDeletedFalseAndDraftFalse(ids, pr))
                    : messageRepository.searchByFolders(ids, kw, pr);
        } else {
            p = kw == null
                    ? (isDraft
                        ? messageRepository.findByFolderIdAndDeletedFalseAndDraftTrue(folderId, pr)
                        : messageRepository.findByFolderIdAndDeletedFalseAndDraftFalse(folderId, pr))
                    : messageRepository.searchByFolder(folderId, kw, pr);
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
        // 实际 SMTP 发送
        String secret = accountService.decryptSecret(account.getId());
        smtpTransport.send(account, secret, req);
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
        return messageRepository.save(m).getId();
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

    private String stripHtml(String html) {
        if (html == null || html.isBlank()) return "";
        return org.jsoup.Jsoup.parse(html).text();
    }
}