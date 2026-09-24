package com.mail.mail;

import com.mail.account.domain.MailAccount;
import com.mail.common.BizException;
import com.mail.mail.dto.ComposeRequest;
import com.mail.mail.domain.AttachmentMeta;
import com.mail.mail.domain.AttachmentMetaRepository;
import com.mail.storage.StorageRouter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import jakarta.mail.Authenticator;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.util.ByteArrayDataSource;

import jakarta.activation.DataHandler;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * SMTP 发送。根据账户配置（STARTTLS/SSL）建立会话并发送，支持带附件的 multipart/mixed 邮件。
 */
@Service
@RequiredArgsConstructor
public class SmtpTransport {

    private final AttachmentMetaRepository attachmentMetaRepository;
    private final StorageRouter storageRouter;

    /**
     * 发送邮件。password 为解密后的账户凭据。
     */
    public void send(MailAccount account, String password, ComposeRequest req) {
        Properties props = new Properties();
        String host = account.getSmtpHost() != null ? account.getSmtpHost() : account.getHost();
        int port = account.getSmtpPort() != null ? account.getSmtpPort() : defaultSmtpPort(account.getSmtpEncryption());
        String enc = account.getSmtpEncryption() != null ? account.getSmtpEncryption() : "STARTTLS";
        // 465 是 SMTPS 隐式 TLS 端口：若账户误配为 STARTTLS，明文握手必然失败（收得到、发不出）。
        // 按端口惯例强制走 SSL，消除这类配置矛盾。
        if (port == 465 && !"SSL".equalsIgnoreCase(enc)) {
            enc = "SSL";
        }
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", String.valueOf(port));
        props.put("mail.smtp.auth", "true");
        // 与 IMAP 侧对齐：SSL 用 mail.smtp.ssl.enable 走 JavaMail 的 JSSE 工厂（带 SNI/主机名校验），
        // 而非废弃的 socketFactory 方式——后者对 465/SSL 服务商会握手失败（收得到、发不出）。
        if ("SSL".equalsIgnoreCase(enc)) {
            props.put("mail.smtp.ssl.enable", "true");
        } else if ("STARTTLS".equalsIgnoreCase(enc)) {
            props.put("mail.smtp.starttls.enable", "true");
        }
        // 连接/读/写超时：避免服务器不可达时无限挂起
        props.put("mail.smtp.connectiontimeout", "15000");
        props.put("mail.smtp.timeout", "30000");
        props.put("mail.smtp.writetimeout", "15000");
        final String user = account.getUsername();
        final String pass = password;
        Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(user, pass);
            }
        });
        try {
            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(account.getEmail(), account.getDisplayName()));
            addRecipients(msg, RecipientType.TO, req.getTo());
            addRecipients(msg, RecipientType.CC, req.getCc());
            addRecipients(msg, RecipientType.BCC, req.getBcc());
            msg.setSubject(req.getSubject());
            buildContent(msg, req, account.getUserId());
            Transport.send(msg);
        } catch (Exception e) {
            throw new com.mail.common.BizException(50001, "邮件发送失败: " + e.getMessage());
        }
    }

    /**
     * 构建邮件正文。无附件时为单一 text/html(或 text/plain) 部分；
     * 有附件时为 multipart/mixed：正文部分 + 若干附件部分。
     */
    private void buildContent(MimeMessage msg, ComposeRequest req, Long ownerUserId) throws Exception {
        boolean hasHtml = req.getBodyHtml() != null && !req.getBodyHtml().isBlank();
        String body = hasHtml ? req.getBodyHtml() : (req.getBodyText() == null ? "" : req.getBodyText());
        List<AttachmentMeta> atts = loadAttachments(req, ownerUserId);
        if (atts.isEmpty()) {
            msg.setContent(body, hasHtml ? "text/html; charset=UTF-8" : "text/plain; charset=UTF-8");
            return;
        }
        Multipart mixed = new MimeMultipart("mixed");
        MimeBodyPart bodyPart = new MimeBodyPart();
        bodyPart.setContent(body, hasHtml ? "text/html; charset=UTF-8" : "text/plain; charset=UTF-8");
        mixed.addBodyPart(bodyPart);
        for (AttachmentMeta meta : atts) {
            mixed.addBodyPart(toAttachmentPart(meta));
        }
        msg.setContent(mixed);
    }

    /**
     * 按 ID 加载附件并校验归属，防止引用他人附件。缺失的 ID 直接报错（避免静默丢附件）。
     */
    private List<AttachmentMeta> loadAttachments(ComposeRequest req, Long ownerUserId) {
        List<Long> ids = req.getAttachments();
        if (ids == null || ids.isEmpty()) return List.of();
        List<AttachmentMeta> out = new ArrayList<>();
        for (Long id : ids) {
            if (id == null) continue;
            AttachmentMeta meta = attachmentMetaRepository.findById(id)
                    .orElseThrow(() -> new BizException("附件不存在: " + id));
            if (ownerUserId != null && !ownerUserId.equals(meta.getUserId())) {
                throw new BizException(40300, "无权引用附件: " + id);
            }
            out.add(meta);
        }
        return out;
    }

    private MimeBodyPart toAttachmentPart(AttachmentMeta meta) throws Exception {
        byte[] data;
        try (InputStream in = storageRouter.byType(meta.getStorageType()).open(meta.getStorageRef())) {
            data = in.readAllBytes();
        }
        String mime = (meta.getMimeType() == null || meta.getMimeType().isBlank())
                ? "application/octet-stream" : meta.getMimeType();
        MimeBodyPart part = new MimeBodyPart();
        part.setDataHandler(new DataHandler(new ByteArrayDataSource(data, mime)));
        String name = meta.getName() == null || meta.getName().isBlank() ? "attachment" : meta.getName();
        part.setFileName(MimeUtility.encodeText(name, "UTF-8", "B"));
        part.setDisposition(Part.ATTACHMENT);
        return part;
    }

    private int defaultSmtpPort(String enc) {
        return "SSL".equalsIgnoreCase(enc) ? 465 : 587;
    }

    /**
     * 将原始收件人列表逐个解析并加入消息。
     * 每个元素可能包含多个地址（以逗号或分号分隔），或带显示名的格式
     * 例如 "张三 <zs@example.com>"。统一用 InternetAddress.parse 解析，
     * 并做 trim / 去除末尾多余的点，避免 "Domain contains dot-dot" 等解析失败。
     */
    private void addRecipients(MimeMessage msg, RecipientType type, java.util.List<String> raw) throws Exception {
        if (raw == null) return;
        for (String entry : raw) {
            if (entry == null || entry.isBlank()) continue;
            String normalized = entry.trim();
            // 去除地址末尾的孤立空白或点，例如 "zs@example.com." / "zs@example.com., "
            normalized = normalized.replaceAll("[\\s.,;，；]+$", "");
            // InternetAddress.parse 支持逗号/分号分隔的多个地址
            InternetAddress[] parsed = InternetAddress.parse(normalized, false);
            for (InternetAddress addr : parsed) {
                if (addr != null && addr.getAddress() != null && !addr.getAddress().isBlank()) {
                    msg.addRecipient(type, addr);
                }
            }
        }
    }
}