package com.mail.mail;

import com.mail.account.domain.MailAccount;
import com.mail.common.BizException;
import com.mail.common.MailErrors;
import com.mail.mail.dto.ComposeRequest;
import com.mail.mail.domain.AttachmentMeta;
import com.mail.mail.domain.AttachmentMetaRepository;
import com.mail.protocol.TlsProps;
import com.mail.storage.StorageRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import jakarta.mail.Address;
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
@Slf4j
@Service
@RequiredArgsConstructor
public class SmtpTransport {

    private final AttachmentMetaRepository attachmentMetaRepository;
    private final StorageRouter storageRouter;

    /**
     * 发送邮件。password 为解密后的账户凭据。
     */
    public void send(MailAccount account, String password, ComposeRequest req) {
        Properties props = propsOf(account);
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
        } catch (jakarta.mail.internet.AddressException e) {
            // 用户可直接自行修正的一类：收件人写法不合法，明确指出是哪一个地址
            throw new com.mail.common.BizException(40002,
                    "收件人地址格式不正确：" + e.getRef() + "，请填写如 name@example.com 的完整地址");
        } catch (jakarta.mail.SendFailedException e) {
            throw new com.mail.common.BizException(50003, sendFailed(e, smtpTarget(account)), e);
        } catch (Exception e) {
            throw new com.mail.common.BizException(50001, MailErrors.explain(e, smtpTarget(account)), e);
        }
    }

    /** 账户实际使用的发信端点描述，用于错误信息里对照配置定位问题。 */
    public String smtpTarget(MailAccount account) {
        String host = account.getSmtpHost() != null ? account.getSmtpHost() : account.getHost();
        int port = account.getSmtpPort() != null
                ? account.getSmtpPort() : defaultSmtpPort(account.getSmtpEncryption());
        return "SMTP " + host + ":" + port + "（" + encLabel(account.getSmtpEncryption(), port) + "）";
    }

    private static String encLabel(String enc, int port) {
        if ("SSL".equalsIgnoreCase(enc) || port == 465) return "SSL 隐式 TLS";
        if ("NONE".equalsIgnoreCase(enc)) return "不加密";
        return "STARTTLS";
    }

    /**
     * 服务器拒收：把被拒的收件人逐个列出，避免只给一句英文 550。
     * 三类地址（无效/合法但被拒/未送达）合并展示，末尾附服务器原文便于反馈给邮箱服务商。
     */
    private String sendFailed(jakarta.mail.SendFailedException e, String target) {
        List<String> bad = new ArrayList<>();
        for (Address[] group : new Address[][] { e.getInvalidAddresses(), e.getValidUnsentAddresses() }) {
            if (group == null) continue;
            for (Address a : group) bad.add(a.toString());
        }
        StringBuilder sb = new StringBuilder();
        sb.append(bad.isEmpty() ? "发送被服务器拒绝" : "以下收件人被服务器拒绝：" + String.join("、", bad));
        if (e.getNextException() != null && e.getNextException().getMessage() != null) {
            sb.append("，服务器返回：").append(e.getNextException().getMessage().trim());
        }
        sb.append('（').append(target).append('）');
        return sb.toString();
    }

    /**
     * 构建邮件正文。
     * <p>正文若引用了本系统附件图片（/api/attachments/{id}/content 或 /api/inline/{id}），
     * 这些图片会被改写为 {@code cid:...} 并作为 {@code multipart/related} 的内联部分随信发出，
     * 收件方（含离线/屏蔽外链的客户端）即可直接显示正文图片。
     * <p>叠加普通附件时外层为 {@code multipart/mixed}：正文(或 related) + 附件部分。
     */
    private void buildContent(MimeMessage msg, ComposeRequest req, Long ownerUserId) throws Exception {
        boolean hasHtml = req.getBodyHtml() != null && !req.getBodyHtml().isBlank();
        String contentType = hasHtml ? "text/html; charset=UTF-8" : "text/plain; charset=UTF-8";
        String body = hasHtml ? req.getBodyHtml() : (req.getBodyText() == null ? "" : req.getBodyText());

        // 普通附件：显式引用的 id 列表，剔除正文中已作为内联图片的部分，避免重复
        List<AttachmentMeta> atts = loadAttachments(req, ownerUserId);
        // 正文内联图片：扫描正文 HTML 引用的附件 id
        List<AttachmentMeta> inlineImgs = loadInlineImages(body, atts, ownerUserId);
        // 内联图片重写：正文 src/href 的附件地址 → cid
        String rewritten = rewriteInlineSrc(body, inlineImgs);

        // 正文 + 内联图片 => multipart/related；无内联图片 => 单一正文部分
        Object bodyContent;
        if (inlineImgs.isEmpty()) {
            bodyContent = rewritten;
        } else {
            MimeMultipart related = new MimeMultipart("related");
            MimeBodyPart textPart = new MimeBodyPart();
            textPart.setContent(rewritten, contentType);
            related.addBodyPart(textPart);
            for (AttachmentMeta meta : inlineImgs) {
                related.addBodyPart(toInlinePart(meta));
            }
            bodyContent = related;
        }

        if (atts.isEmpty()) {
            if (bodyContent instanceof String) {
                msg.setContent(bodyContent, contentType);
            } else {
                msg.setContent((Multipart) bodyContent);
            }
            return;
        }
        // 有普通附件：外层 multipart/mixed
        Multipart mixed = new MimeMultipart("mixed");
        MimeBodyPart bodyPart = new MimeBodyPart();
        if (bodyContent instanceof String) {
            bodyPart.setContent((String) bodyContent, contentType);
        } else {
            bodyPart.setContent((Multipart) bodyContent);
        }
        mixed.addBodyPart(bodyPart);
        for (AttachmentMeta meta : atts) {
            mixed.addBodyPart(toAttachmentPart(meta));
        }
        msg.setContent(mixed);
    }

    /** 正文内联图片引用的 URL 模式：/api/attachments/{id}/content 或 /api/inline/{id}。 */
    private static final java.util.regex.Pattern INLINE_URL =
            java.util.regex.Pattern.compile("/api/(?:attachments/(\\d+)/content|inline/(\\d+))");
    private static final String CID_DOMAIN = "@mail-client";

    /**
     * 扫描正文，收集其引用的、且未出现在普通附件列表中的图片附件。缺失 id 静默跳过
     * （正文可能含历史草稿遗留的失效引用），归属校验失败则报错。
     */
    private List<AttachmentMeta> loadInlineImages(String body, List<AttachmentMeta> atts, Long ownerUserId) {
        if (body == null || body.isBlank()) return List.of();
        java.util.Set<Long> existing = new java.util.HashSet<>();
        for (AttachmentMeta a : atts) existing.add(a.getId());
        java.util.LinkedHashSet<Long> ids = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher m = INLINE_URL.matcher(body);
        while (m.find()) {
            String g = m.group(1) != null ? m.group(1) : m.group(2);
            if (g != null) ids.add(Long.valueOf(g));
        }
        List<AttachmentMeta> out = new ArrayList<>();
        for (Long id : ids) {
            if (id == null || existing.contains(id)) continue;
            AttachmentMeta meta = attachmentMetaRepository.findById(id).orElse(null);
            if (meta == null) continue;
            if (ownerUserId != null && !ownerUserId.equals(meta.getUserId())) {
                throw new BizException(40300, "无权引用附件: " + id);
            }
            out.add(meta);
        }
        return out;
    }

    /** 将正文里的附件 URL 引用改写为 cid:{attachmentId}@mail-client。 */
    private String rewriteInlineSrc(String body, List<AttachmentMeta> inlineImgs) {
        String out = body;
        for (AttachmentMeta meta : inlineImgs) {
            String cid = "cid:" + meta.getId() + CID_DOMAIN;
            out = out.replace("/api/attachments/" + meta.getId() + "/content", cid);
            out = out.replace("/api/inline/" + meta.getId(), cid);
        }
        return out;
    }

    /** 内联图片部分：设置 Content-ID（尖括号）与 inline 处置，不暴露为附件文件名。 */
    private MimeBodyPart toInlinePart(AttachmentMeta meta) throws Exception {
        byte[] data;
        try (InputStream in = storageRouter.byType(meta.getStorageType()).open(meta.getStorageRef())) {
            data = in.readAllBytes();
        }
        String mime = (meta.getMimeType() == null || meta.getMimeType().isBlank())
                ? "application/octet-stream" : meta.getMimeType();
        MimeBodyPart part = new MimeBodyPart();
        part.setDataHandler(new DataHandler(new ByteArrayDataSource(data, mime)));
        part.setHeader("Content-ID", "<" + meta.getId() + CID_DOMAIN + ">");
        part.setDisposition(Part.INLINE);
        if (meta.getName() != null && !meta.getName().isBlank()) {
            part.setFileName(MimeUtility.encodeText(meta.getName(), "UTF-8", "B"));
        }
        return part;
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

    /**
     * 按账户的「发信」配置构建 SMTP 会话属性。加密方式与收信侧相互独立。
     */
    private Properties propsOf(MailAccount account) {
        Properties props = new Properties();
        String host = account.getSmtpHost() != null ? account.getSmtpHost() : account.getHost();
        int port = account.getSmtpPort() != null
                ? account.getSmtpPort() : defaultSmtpPort(account.getSmtpEncryption());
        String enc = account.getSmtpEncryption() != null ? account.getSmtpEncryption() : "STARTTLS";
        // 465 是 SMTPS 隐式 TLS 端口：若账户误配为 STARTTLS，明文握手必然失败（收得到、发不出）。
        // 按端口惯例强制走 SSL，消除这类配置矛盾。
        if (port == 465 && !"SSL".equalsIgnoreCase(enc)) {
            enc = "SSL";
        }
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", String.valueOf(port));
        props.put("mail.smtp.auth", "true");
        // 兼容性：显式启用 EHLO 并给出主机名，某些国内邮件服务商会因 HELO/EHLO 无效而拒收
        props.put("mail.smtp.ehlo", "true");
        props.put("mail.smtp.localhost", "localhost");
        // 认证机制优先使用服务器已扩展的：PLAIN,LOGIN,CRAM-MD5,DIGEST-MD5 覆盖大多数服务商；
        // 不含 NTLM，避免某些老 IIS SMTP 报错。
        props.put("mail.smtp.auth.mechanisms", "PLAIN LOGIN CRAM-MD5 DIGEST-MD5");
        // 部分服务器在 STARTTLS 之前禁用认证，允许在 TLS 建立之后再发起 AUTH
        props.put("mail.smtp.smtp.allow8bitmime", "true");
        props.put("mail.mime.charset", "utf-8");
        // 与 IMAP 侧对齐：SSL 用 mail.smtp.ssl.enable 走 JavaMail 的 JSSE 工厂（带 SNI/主机名校验），
        // 而非废弃的 socketFactory 方式——后者对 465/SSL 服务商会握手失败（收得到、发不出）。
        TlsProps.apply(props, "mail.smtp", enc, account.isIgnoreCertError());
        // 连接/读/写超时：避免服务器不可达时无限挂起
        props.put("mail.smtp.connectiontimeout", "15000");
        props.put("mail.smtp.timeout", "30000");
        props.put("mail.smtp.writetimeout", "15000");
        return props;
    }

    /**
     * 仅登录校验 SMTP 连通性与凭据，不投递任何邮件。成功返回 {@code null}，失败返回原因。
     */
    public String testConnection(MailAccount account, String password) {
        Properties props = propsOf(account);
        try (Transport t = Session.getInstance(props).getTransport("smtp")) {
            t.connect(props.getProperty("mail.smtp.host"),
                    Integer.parseInt(props.getProperty("mail.smtp.port")),
                    account.getUsername(), password);
            return null;
        } catch (Exception e) {
            log.warn("SMTP 连接失败 {}: {}", account.getEmail(), e.getMessage());
            return MailErrors.explain(e, "SMTP " + props.getProperty("mail.smtp.host")
                    + ":" + props.getProperty("mail.smtp.port"));
        }
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