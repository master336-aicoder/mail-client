package com.mail.transfer;

import com.mail.common.BizException;
import com.mail.mail.domain.AttachmentMeta;
import com.mail.mail.domain.AttachmentMetaRepository;
import com.mail.mail.domain.Message;
import com.mail.mail.domain.MessageRepository;
import com.mail.storage.AttachmentStorage;
import com.mail.storage.StorageRouter;
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
 * <p>说明：所在环境仅引入 java-libpst（只读解析，无法生成二进制 PST）。
 * 因此导出采用 <b>EML 打包</b>（每封邮件一个 .eml，压缩为 .zip），
 * 该产物可被 Outlook / 主流邮件客户端导入，并满足“导出完成后提供下载地址”的要求。
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

    @Value("${mail.app.pst-export-dir:./data/exports}")
    private String pstDir;

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

        progress.report(10, "开始导出邮件…");

        // 收集选定文件夹下的邮件（未删除、非草稿按文件夹导出；草稿仅草稿箱导出）
        long total = 0;
        for (Long fid : folderIds) {
            total += messageRepository.countByFolderIdAndDeletedFalse(fid);
        }
        if (total == 0) {
            progress.report(100, "所选文件夹无邮件，导出完成");
            task.setResult(null);
            return;
        }

        Path dir = Paths.get(pstDir, "user-" + userId);
        Files.createDirectories(dir);
        String stamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
        Path zip = dir.resolve("export-" + stamp + ".zip");

        long done = 0;
        try (OutputStream fos = Files.newOutputStream(zip);
             ZipOutputStream zos = new ZipOutputStream(fos, StandardCharsets.UTF_8)) {
            int idx = 1;
            for (Long fid : folderIds) {
                List<Message> messages = messageRepository.findByFolderIdAndDeletedFalse(fid);
                for (Message m : messages) {
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

        // 记录相对下载路径，前端据此拼出下载地址
        task.setResult("user-" + userId + "/" + zip.getFileName().toString());
        progress.report(100, "导出完成，共 " + done + " 封邮件");
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
