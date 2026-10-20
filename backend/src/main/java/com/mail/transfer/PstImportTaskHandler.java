package com.mail.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mail.common.BizException;
import com.mail.folder.domain.Folder;
import com.mail.folder.domain.FolderRepository;
import com.mail.mail.domain.AttachmentMeta;
import com.mail.mail.domain.AttachmentMetaRepository;
import com.mail.mail.domain.Message;
import com.mail.mail.domain.MessageRepository;
import com.mail.storage.AttachmentStorage;
import com.mail.storage.StorageRouter;
import com.mail.task.AttachmentFiles;
import com.mail.task.Progress;
import com.mail.task.TaskHandler;
import com.mail.task.domain.Task;
import com.pff.PSTAttachment;
import com.pff.PSTException;
import com.pff.PSTFile;
import com.pff.PSTFolder;
import com.pff.PSTMessage;
import com.pff.PSTObject;
import com.pff.PSTRecipient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PST 导入后台任务：读取上传的 .pst，按文件夹层级在本账户下重建邮件与附件。
 *
 * <p>依赖 java-libpst（只读）。OST 不被该库支持，上传阶段已按扩展名拦截。
 * 去重以 remoteUid = pst:{folderNodeId}:{messageId|subjectHash} 为准，重复导入同一文件不会翻倍。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PstImportTaskHandler implements TaskHandler {

    public static final String TYPE = "pst_import";

    private final MessageRepository messageRepository;
    private final AttachmentMetaRepository attachmentMetaRepository;
    private final FolderRepository folderRepository;
    private final StorageRouter storageRouter;
    private final AttachmentFiles attachmentFiles;
    private final ObjectMapper objectMapper;

    /** PST 路径 → 本地 folderId 的映射（单次任务内有效）。 */
    private final Map<String, Long> folderMap = new HashMap<>();

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String name() {
        return "PST 导入";
    }

    @Override
    @SuppressWarnings("unchecked")
    public void run(Task task, Progress progress) throws Exception {
        folderMap.clear();
        Long userId = task.getUserId();
        Long accountId = task.getAccountId();
        String paramsJson = task.getParams();
        if (paramsJson == null || paramsJson.isBlank()) throw new BizException("导入任务缺少参数");

        Map<String, Object> params = objectMapper.readValue(paramsJson, Map.class);
        String uploadId = String.valueOf(params.get("uploadId"));
        String rootName = params.get("folderName") == null ? "PST 导入" : String.valueOf(params.get("folderName"));

        Path pst = attachmentFiles.resolve("import/user-" + userId + "/" + uploadId + ".pst");
        progress.report(2, "正在打开 PST 文件…");

        int[] imported = {0}, skipped = {0};
        PSTFile pstFile;
        try {
            pstFile = new PSTFile(pst.toFile());
        } catch (PSTException | java.io.IOException e) {
            throw new BizException("无法解析 PST 文件（可能已损坏，或实为 OST 格式）：" + e.getMessage());
        }

        try {
            PSTFolder root = pstFile.getRootFolder();
            long total = countEmails(root);
            if (total == 0) {
                progress.result("PST 中没有邮件");
                progress.report(100, "PST 中没有邮件");
                return;
            }
            // 顶层文件夹落在用户指定的 rootName 之下
            importFolder(root, null, rootName, userId, accountId, progress, total, imported, skipped);
        } finally {
            try { pstFile.close(); } catch (Exception ignore) { }
        }

        long size = attachmentFiles.sizeOf("import/user-" + userId + "/" + uploadId + ".pst");
        progress.report(100, String.format("导入完成：新增 %d 封，跳过 %d 封", imported[0], skipped[0]));
        progress.result(String.format("导入 %d 封邮件（跳过重复/非邮件 %d 项）", imported[0], skipped[0]));
        log.info("PST 导入完成 user={} account={} 新增={} 跳过={} 源大小={}B",
                userId, accountId, imported[0], skipped[0], size);
    }

    /** 递归导入一个 PST 文件夹及其子文件夹；返回本层（含子树）新增数由 imported[] 累计。 */
    private void importFolder(PSTFolder pf, Long parentFolderId, String folderName,
                              Long userId, Long accountId, Progress progress,
                              long total, int[] imported, int[] skipped) throws Exception {
        String pathKey = parentFolderId + "/" + folderName;
        Long localFolderId = folderMap.get(pathKey);
        if (localFolderId == null) {
            localFolderId = ensureFolder(userId, accountId, parentFolderId, folderName);
            folderMap.put(pathKey, localFolderId);
        }

        // 本层邮件
        pf.moveChildCursorTo(0);
        try {
            PSTObject child = pf.getNextChild();
            while (child != null) {
                try {
                    if (child instanceof PSTMessage pm) {
                        String mc = safe(pm.getMessageClass());
                        if (mc != null && mc.startsWith("IPM.Note")) {
                            importMessage(pm, localFolderId, userId, accountId, imported, skipped);
                        } else {
                            skipped[0]++;   // 会议/任务/联系人等非邮件项
                        }
                        int done = imported[0] + skipped[0];
                        if (done % 10 == 0) {
                            progress.report(Math.min(95, 2 + (int) (93.0 * done / total)),
                                    "已处理 " + done + "/" + total);
                        }
                    }
                } catch (Exception perChild) {
                    skipped[0]++;
                    log.warn("跳过无法解析的邮件项（文件夹 {}）: {}", folderName, perChild.getMessage());
                }
                child = pf.getNextChild();
            }
        } catch (Exception iterate) {
            log.warn("文件夹 [{}] 子项遍历中断: {}", folderName, iterate.getMessage());
        }

        // 子文件夹（单个损坏/不受支持的节点不影响其它文件夹）
        for (PSTFolder sub : safeSubFolders(pf, folderName)) {
            String subName = safeDisplayName(sub);
            try {
                importFolder(sub, localFolderId, subName == null ? "未命名" : subName,
                        userId, accountId, progress, total, imported, skipped);
            } catch (Exception subFail) {
                log.warn("跳过子文件夹 [{}]: {}", subName, subFail.getMessage());
            }
        }
    }

    /** 取子文件夹列表；Outlook 搜索/视图等特殊节点常导致 java-libpst 抛错，此处吞掉并返回可解析的部分。 */
    private List<PSTFolder> safeSubFolders(PSTFolder pf, String folderName) {
        try {
            List<PSTFolder> subs = pf.getSubFolders();
            return subs == null ? List.of() : subs;
        } catch (Exception e) {
            log.warn("无法枚举文件夹 [{}] 的子文件夹，跳过: {}", folderName, e.getMessage());
            return List.of();
        }
    }

    private void importMessage(PSTMessage pm, Long folderId, Long userId, Long accountId,
                               int[] imported, int[] skipped) {
        String remoteUid = "pst:" + pm.getDescriptorNodeId();
        String msgId = safe(pm.getInternetMessageId());
        if (msgId != null && !msgId.isBlank()) remoteUid += ":" + msgId;
        else remoteUid += ":" + Integer.toHexString(
                (safe(pm.getSubject()) + "|" + pm.getMessageDeliveryTime()).hashCode());
        if (remoteUid.length() > 60) remoteUid = remoteUid.substring(0, 60);

        if (messageRepository.findByAccountIdAndFolderIdAndRemoteUid(accountId, folderId, remoteUid).isPresent()) {
            skipped[0]++;
            return;
        }

        Message m = new Message();
        m.setUserId(userId);
        m.setAccountId(accountId);
        m.setFolderId(folderId);
        m.setRemoteUid(remoteUid);
        m.setMessageId(msgId);
        m.setSubject(truncate(safe(pm.getSubject()), 1000));
        m.setSender(truncate(safe(pm.getSenderName()), 250));
        m.setSenderEmail(truncate(firstNonBlank(safe(pm.getSenderEmailAddress()), safe(pm.getReturnPath())), 250));

        fillRecipients(pm, m);

        String html = safe(pm.getBodyHTML());
        String text = safe(pm.getBody());
        if (html != null && !html.isBlank()) m.setBodyHtml(html);
        if (text != null && !text.isBlank()) m.setBodyText(text);
        if ((html == null || html.isBlank()) && (text == null || text.isBlank())) {
            String rtf = safeOrNull(pm);
            if (rtf != null) m.setBodyText(rtf);
        }

        Date dt = pm.getMessageDeliveryTime() != null ? pm.getMessageDeliveryTime() : pm.getClientSubmitTime();
        m.setDate(dt == null ? LocalDateTime.now() : LocalDateTime.ofInstant(dt.toInstant(), ZoneId.systemDefault()));

        int flags = 0;
        if (pm.isRead()) flags |= 1;
        m.setFlags(flags);
        m.setSize(Math.max(0, pm.getMessageSize()));
        String previewSrc = (text != null && !text.isBlank()) ? text : html;
        m.setPreview(truncate(previewSrc == null ? "" : stripTags(previewSrc), 480));
        m.setDraft(false);
        m.setDeleted(false);

        Message saved = messageRepository.save(m);

        int attCount = importAttachments(pm, saved, userId, accountId);
        saved.setHasAttach(attCount > 0);
        if (attCount > 0) messageRepository.save(saved);
        imported[0]++;
    }

    private int importAttachments(PSTMessage pm, Message m, Long userId, Long accountId) {
        int persisted = 0;
        AttachmentStorage storage = storageRouter.defaultStorage();
        int n = pm.getNumberOfAttachments();
        for (int i = 0; i < n; i++) {
            try {
                PSTAttachment att = pm.getAttachment(i);
                int method = att.getAttachMethod();
                // 仅内联存储的附件可取流；引用型（BY_REFERENCE）无本地数据可取
                if (method != PSTAttachment.ATTACHMENT_METHOD_BY_VALUE
                        && method != PSTAttachment.ATTACHMENT_METHOD_EMBEDDED) continue;
                byte[] data;
                try (InputStream in = att.getFileInputStream()) {
                    if (in == null) continue;
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int read;
                    while ((read = in.read(buf)) > 0) bos.write(buf, 0, read);
                    data = bos.toByteArray();
                }
                if (data.length == 0) continue;
                String fname = firstNonBlank(safe(att.getLongFilename()), safe(att.getFilename()), "附件");
                String mime = firstNonBlank(safe(att.getMimeTag()), "application/octet-stream");
                String cid = safe(att.getContentId());
                boolean inline = cid != null && !cid.isBlank() && att.isAttachmentInvisibleInHtml();
                String key = userId + "/attach/" + m.getId() + "/" + System.currentTimeMillis() + "-" + i + "-"
                        + Math.abs(fname.hashCode());
                String ref = storage.save(key, new ByteArrayInputStream(data), data.length, mime);

                AttachmentMeta meta = new AttachmentMeta();
                meta.setUserId(userId);
                meta.setMessageId(m.getId());
                meta.setAccountId(accountId);
                meta.setName(truncate(fname, 500));
                meta.setMimeType(truncate(mime, 250));
                meta.setSize((long) data.length);
                meta.setStorageRef(ref);
                meta.setStorageType(storage.type());
                meta.setContentId(cid);
                meta.setDisposition(inline ? "inline" : "attachment");
                attachmentMetaRepository.save(meta);
                persisted++;
            } catch (Exception e) {
                log.warn("导入跳过附件 #{}（邮件 {}）: {}", i, m.getId(), e.getMessage());
            }
        }
        return persisted;
    }

    private void fillRecipients(PSTMessage pm, Message m) {
        StringBuilder to = new StringBuilder(), cc = new StringBuilder(), bcc = new StringBuilder();
        int rc;
        try { rc = pm.getNumberOfRecipients(); } catch (Exception e) { rc = 0; }
        for (int i = 0; i < rc; i++) {
            try {
                PSTRecipient r = pm.getRecipient(i);
                String addr = firstNonBlank(safe(r.getSmtpAddress()), safe(r.getEmailAddress()));
                if (addr == null) continue;
                String disp = safe(r.getDisplayName());
                String line = (disp != null && !disp.isBlank() && !disp.equals(addr))
                        ? disp + " <" + addr + ">" : addr;
                switch (r.getRecipientType()) {
                    case PSTRecipient.MAPI_CC -> append(cc, line);
                    case PSTRecipient.MAPI_BCC -> append(bcc, line);
                    default -> append(to, line);
                }
            } catch (Exception e) {
                log.debug("读取收件人 #{} 失败: {}", i, e.getMessage());
            }
        }
        if (to.length() > 0) m.setRecipients(truncate(to.toString(), 4000));
        if (cc.length() > 0) m.setCc(truncate(cc.toString(), 4000));
        if (bcc.length() > 0) m.setBcc(truncate(bcc.toString(), 4000));
    }

    private static void append(StringBuilder sb, String line) {
        if (sb.length() > 0) sb.append(", ");
        sb.append(line);
    }

    private Long ensureFolder(Long userId, Long accountId, Long parentId, String name) {
        List<Folder> existing = accountId == null
                ? folderRepository.findByUserIdAndAccountIdIsNullOrderBySeqAsc(userId)
                : folderRepository.findByUserIdAndAccountIdOrderBySeqAsc(userId, accountId);
        for (Folder f : existing) {
            if (eq(f.getParentId(), parentId) && name.equals(f.getName())) return f.getId();
        }
        Folder f = new Folder();
        f.setUserId(userId);
        f.setAccountId(accountId);
        f.setParentId(parentId);
        f.setName(truncate(name, 120));
        f.setSeq(existing.size());
        return folderRepository.save(f).getId();
    }

    /** 估算邮件总数用于进度（含子树），仅统计 IPM.Note；不受支持的搜索/视图节点跳过。 */
    private long countEmails(PSTFolder pf) {
        long count = 0;
        try {
            pf.moveChildCursorTo(0);
            PSTObject child = pf.getNextChild();
            while (child != null) {
                if (child instanceof PSTMessage pm) {
                    String mc = safe(pm.getMessageClass());
                    if (mc != null && mc.startsWith("IPM.Note")) count++;
                }
                child = pf.getNextChild();
            }
        } catch (Exception e) {
            log.warn("统计邮件数时遍历中断: {}", e.getMessage());
        }
        for (PSTFolder sub : safeSubFolders(pf, "统计")) count += countEmails(sub);
        return count;
    }

    // ---------------------------------------------------------------- helpers

    private static String safeDisplayName(PSTFolder f) {
        try { String n = f.getDisplayName(); return n == null || n.isBlank() ? null : n; }
        catch (Exception e) { return null; }
    }

    private static String safeOrNull(PSTMessage pm) {
        try { String r = pm.getRTFBody(); return r; } catch (Exception e) { return null; }
    }

    private static String safe(String v) { return v == null ? null : v.trim(); }

    private static boolean eq(Long a, Long b) { return a == null ? b == null : a.equals(b); }

    private static String firstNonBlank(String... vs) {
        for (String v : vs) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private static String truncate(String v, int max) {
        if (v == null) return null;
        return v.length() <= max ? v : v.substring(0, max);
    }

    private static String stripTags(String html) {
        return html.replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ").trim();
    }
}
