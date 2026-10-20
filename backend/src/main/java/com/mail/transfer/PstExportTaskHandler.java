package com.mail.transfer;

import com.mail.common.BizException;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * PST 导出后台任务。在独立线程池中异步执行，导出指定账户、指定文件夹下的邮件。
 *
 * <p>说明：所在环境仅引入 java-libpst（只读解析，无法生成二进制 PST/OST）。
 * 因此导出采用业界标准的邮件归档格式，均可被 Outlook / Thunderbird / 主流邮件客户端导入：
 * <ul>
 *   <li><b>zip</b>（默认）：每封邮件一个 .eml，压缩为 .zip；</li>
 *   <li><b>mbox</b>：单个 .mbox 邮箱归档文件（RFC822 + "From " 分隔线）。</li>
 * </ul>
 * 产物可被邮件客户端导入，并满足“导出完成后提供下载地址”的要求。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PstExportTaskHandler implements TaskHandler {

    public static final String TYPE = "pst_export";

    private final MessageRepository messageRepository;
    private final ObjectMapper objectMapper;
    private final AttachmentMetaRepository attachmentMetaRepository;
    private final StorageRouter storageRouter;
    private final AttachmentFiles attachmentFiles;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String name() {
        return "PST 导出";
    }

    @Override
    public void run(Task task, Progress progress) throws Exception {
        Long userId = task.getUserId();
        String paramsJson = task.getParams();
        if (paramsJson == null || paramsJson.isBlank()) {
            throw new BizException("导出任务缺少参数");
        }
        Map<String, Object> params = objectMapper.readValue(paramsJson, Map.class);
        List<?> folderIdsRaw = (List<?>) params.get("folderIds");
        if (folderIdsRaw == null || folderIdsRaw.isEmpty()) {
            throw new BizException("未选择要导出的文件夹");
        }
        List<Long> folderIds = folderIdsRaw.stream()
                .map(o -> ((Number) o).longValue()).toList();
        String format = normalizeFormat(params.get("format"));

        progress.report(10, "开始导出邮件…");

        // 收集选定文件夹下的邮件（未删除、非草稿按文件夹导出；草稿仅草稿箱导出）
        long total = 0;
        for (Long fid : folderIds) {
            total += messageRepository.countByFolderIdAndDeletedFalse(fid);
        }
        if (total == 0) {
            progress.result("所选文件夹无邮件");
            progress.report(100, "所选文件夹无邮件，导出完成");
            return;
        }

        String relativeDir = "user-" + userId;
        Path dir = attachmentFiles.ensureDir(relativeDir);
        String stamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());

        long done;
        Path artifact;
        if ("mbox".equals(format)) {
            artifact = dir.resolve("export-" + stamp + ".mbox");
            done = exportMbox(artifact, folderIds, total, progress);
            String relative = relativeDir + "/" + artifact.getFileName().toString();
            progress.attach(artifact.getFileName().toString(), relative, Files.size(artifact), "application/octet-stream");
        } else {
            artifact = dir.resolve("export-" + stamp + ".zip");
            done = exportZip(artifact, folderIds, total, progress);
            String relative = relativeDir + "/" + artifact.getFileName().toString();
            progress.attach(artifact.getFileName().toString(), relative, Files.size(artifact), "application/zip");
        }

        String label = "mbox".equals(format) ? "MBOX" : "EML(ZIP)";
        progress.result("导出 " + done + " 封邮件（" + label + "）");
        progress.report(100, "导出完成，共 " + done + " 封邮件");
    }

    /** 只接受 zip / mbox，其它（含 null）回退默认 zip。 */
    private String normalizeFormat(Object raw) {
        String v = raw == null ? "" : String.valueOf(raw).trim().toLowerCase();
        return "mbox".equals(v) ? "mbox" : "zip";
    }

    /** 导出为 .zip：每封邮件一个 .eml 条目。 */
    private long exportZip(Path zip, List<Long> folderIds, long total, Progress progress) throws Exception {
        long done = 0;
        try (OutputStream fos = Files.newOutputStream(zip);
             ZipOutputStream zos = new ZipOutputStream(fos, StandardCharsets.UTF_8)) {
            int idx = 1;
            for (Long fid : folderIds) {
                for (Message m : messageRepository.findByFolderIdAndDeletedFalse(fid)) {
                    zos.putNextEntry(new ZipEntry(safeName(idx++) + ".eml"));
                    zos.write(buildEml(m).getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();
                    done++;
                    if (done % 20 == 0 || done == total) {
                        progress.report(10 + (int) (80.0 * done / total), "已导出 " + done + "/" + total);
                    }
                }
            }
        }
        return done;
    }

    /**
     * 导出为单个 .mbox 邮箱归档：每封邮件以 mbox 分隔线（"From " + 发件地址 + 时间）开头，
     * 正文行首的 "From " 转义为 ">From "（mboxo 规则），避免解析歧义。
     */
    private long exportMbox(Path mbox, List<Long> folderIds, long total, Progress progress) throws Exception {
        long done = 0;
        SimpleDateFormat fromLine = new SimpleDateFormat("EEE MMM dd HH:mm:ss yyyy", java.util.Locale.US);
        try (OutputStream fos = Files.newOutputStream(mbox);
             java.io.BufferedWriter w = new java.io.BufferedWriter(
                     new java.io.OutputStreamWriter(fos, StandardCharsets.UTF_8))) {
            for (Long fid : folderIds) {
                for (Message m : messageRepository.findByFolderIdAndDeletedFalse(fid)) {
                    String addr = m.getSenderEmail() == null || m.getSenderEmail().isBlank()
                            ? "unknown@example.com" : m.getSenderEmail().replaceAll("\\s", "");
                    Date when = m.getDate() != null
                            ? Date.from(m.getDate().atZone(java.time.ZoneId.systemDefault()).toInstant())
                            : new Date();
                    w.write("From " + addr + " " + fromLine.format(when) + "\n");
                    for (String line : buildEml(m).split("\r?\n", -1)) {
                        // 行首 "From " 转义为 ">From "（mboxo 规则），避免被误判为分隔线
                        w.write(line.startsWith("From ") ? ">" + line : line);
                        w.write("\n");
                    }
                    w.write("\n"); // 消息间空行分隔
                    done++;
                    if (done % 20 == 0 || done == total) {
                        progress.report(10 + (int) (80.0 * done / total), "已导出 " + done + "/" + total);
                    }
                }
            }
        }
        return done;
    }

    private String safeName(int i) {
        return String.format("mail-%05d", i);
    }

    /**
     * 将数据库中的邮件行还原为 EML 文本。保留发件人/收件人/主题/正文与头部；
     * 若该邮件含附件，则输出 multipart/mixed 并内联 base64 附件，避免导出丢附件。
     */
    private String buildEml(Message m) {
        StringBuilder sb = new StringBuilder();
        if (m.getSenderEmail() != null && !m.getSenderEmail().isBlank()) {
            sb.append("From: ").append(m.getSender() == null || m.getSender().isBlank()
                    ? m.getSenderEmail() : encodeHeader(m.getSender()) + " <" + m.getSenderEmail() + ">").append("\n");
        }
        if (m.getRecipients() != null && !m.getRecipients().isBlank()) {
            sb.append("To: ").append(m.getRecipients()).append("\n");
        }
        if (m.getCc() != null && !m.getCc().isBlank()) {
            sb.append("Cc: ").append(m.getCc()).append("\n");
        }
        if (m.getBcc() != null && !m.getBcc().isBlank()) {
            sb.append("Bcc: ").append(m.getBcc()).append("\n");
        }
        sb.append("Subject: ").append(encodeHeader(m.getSubject() == null ? "" : m.getSubject())).append("\n");
        if (m.getDate() != null) {
            sb.append("Date: ").append(m.getDate()).append("\n");
        }
        sb.append("MIME-Version: 1.0\n");

        boolean html = m.getBodyHtml() != null && !m.getBodyHtml().isBlank();
        String body = html ? m.getBodyHtml() : (m.getBodyText() == null ? "" : m.getBodyText());
        String bodyType = html ? "text/html" : "text/plain";

        List<AttachmentMeta> atts = attachmentMetaRepository.findByMessageId(m.getId());
        if (atts == null || atts.isEmpty()) {
            sb.append("Content-Type: ").append(bodyType).append("; charset=UTF-8\n");
            sb.append("Content-Transfer-Encoding: 8bit\n\n");
            sb.append(body);
            return sb.toString();
        }

        String boundary = "----=_MailClient_" + java.util.UUID.randomUUID().toString().replace("-", "");
        sb.append("Content-Type: multipart/mixed; boundary=\"").append(boundary).append("\"\n\n");
        // 正文部分
        sb.append("--").append(boundary).append("\n");
        sb.append("Content-Type: ").append(bodyType).append("; charset=UTF-8\n");
        sb.append("Content-Transfer-Encoding: 8bit\n\n");
        sb.append(body).append("\n");
        // 附件部分
        for (AttachmentMeta a : atts) {
            byte[] data;
            try (InputStream in = storageRouter.byType(a.getStorageType()).open(a.getStorageRef())) {
                data = in.readAllBytes();
            } catch (Exception e) {
                log.warn("导出跳过无法读取的附件 {}: {}", a.getId(), e.getMessage());
                continue;
            }
            String mime = (a.getMimeType() == null || a.getMimeType().isBlank())
                    ? "application/octet-stream" : a.getMimeType();
            String fname = encodeHeader(a.getName() == null || a.getName().isBlank() ? "attachment" : a.getName());
            sb.append("--").append(boundary).append("\n");
            sb.append("Content-Type: ").append(mime).append("; name=\"").append(fname).append("\"\n");
            sb.append("Content-Transfer-Encoding: base64\n");
            sb.append("Content-Disposition: attachment; filename=\"").append(fname).append("\"\n\n");
            sb.append(java.util.Base64.getMimeEncoder(76, new byte[]{'\r', '\n'}).encodeToString(data)).append("\n");
        }
        sb.append("--").append(boundary).append("--\n");
        return sb.toString();
    }

    /** 对可能含非 ASCII 的头部值做 RFC 2047 编码，失败时回退原值。 */
    private String encodeHeader(String v) {
        if (v == null) return "";
        try {
            return jakarta.mail.internet.MimeUtility.encodeText(v, "UTF-8", "B");
        } catch (Exception e) {
            return v;
        }
    }
}
