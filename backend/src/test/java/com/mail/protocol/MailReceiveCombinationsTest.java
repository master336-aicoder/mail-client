package com.mail.protocol;

import jakarta.mail.Message;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.util.ByteArrayDataSource;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.function.Executable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 收信组合矩阵：主题(有/编码/无) × 正文(HTML/纯文本/related+内联图/无) × 附件(无/单个/多个)，
 * 全部先序列化再从流解析（与 IMAP/POP3 实收一致），验证 .eml/普通附件采集与正文互不污染。
 */
class MailReceiveCombinationsTest {

    private final Session session = Session.getInstance(new Properties());

    private enum Subject { NORMAL, ENCODED, NONE }
    private enum Body { HTML, PLAIN, RELATED, NONE }
    private enum Att { NONE, ONE, MANY }

    private static final byte[] PDF = new byte[]{0x25, 0x50, 0x44, 0x46, -0x01, 0x0A}; // %PDF
    private static final byte[] PNG = Base64.getMimeDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    // ---------- 部件构造 ----------
    private MimeBodyPart textPart(String type, String content) throws Exception {
        MimeBodyPart p = new MimeBodyPart();
        p.setContent(content, type + "; charset=utf-8");
        return p;
    }

    private MimeBodyPart binaryPart(String fileName, String disposition, String mime, byte[] data) throws Exception {
        MimeBodyPart p = new MimeBodyPart();
        p.setDataHandler(new jakarta.activation.DataHandler(new ByteArrayDataSource(data, mime)));
        if (fileName != null) p.setFileName(MimeUtility.encodeText(fileName, "UTF-8", "B"));
        if (disposition != null) p.setDisposition(disposition);
        return p;
    }

    private byte[] innerEml(String subject) throws Exception {
        MimeMessage inner = new MimeMessage(session);
        inner.setFrom(new jakarta.mail.internet.InternetAddress("a@inner.test"));
        inner.setRecipient(Message.RecipientType.TO, new jakarta.mail.internet.InternetAddress("b@inner.test"));
        inner.setSubject(subject);
        inner.setText("inner body");
        inner.saveChanges();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        inner.writeTo(bos);
        return bos.toByteArray();
    }

    private MimeBodyPart imagePartWithCid(String cid) throws Exception {
        MimeBodyPart p = new MimeBodyPart();
        p.setDataHandler(new jakarta.activation.DataHandler(new ByteArrayDataSource(PNG, "image/png")));
        p.setHeader("Content-ID", "<" + cid + ">");
        p.setDisposition(Part.INLINE);
        return p;
    }

    /** 组装整封邮件并序列化回读，模拟真实网络收取。 */
    private MimeMessage build(Subject subj, Body body, Att att) throws Exception {
        MimeMultipart mixed = new MimeMultipart("mixed");

        switch (body) {
            case HTML -> mixed.addBodyPart(textPart("text/html", "<p>正文 <b>HTML</b></p>"));
            case PLAIN -> mixed.addBodyPart(textPart("text/plain", "纯文本正文"));
            case RELATED -> {
                MimeMultipart rel = new MimeMultipart("related");
                MimeBodyPart html = new MimeBodyPart();
                html.setContent("<p>看图 <img src=\"cid:img1\"></p>", "text/html; charset=utf-8");
                rel.addBodyPart(html);
                rel.addBodyPart(imagePartWithCid("img1"));
                MimeBodyPart wrapper = new MimeBodyPart();
                wrapper.setContent(rel);
                mixed.addBodyPart(wrapper);
            }
            case NONE -> { mixed.addBodyPart(textPart("text/plain", "")); } // 无正文（空文本部件）
        }

        switch (att) {
            case NONE -> { /* 无附件 */ }
            case ONE -> mixed.addBodyPart(binaryPart("报告.pdf", Part.ATTACHMENT, "application/pdf", PDF));
            case MANY -> {
                mixed.addBodyPart(binaryPart("报告.pdf", Part.ATTACHMENT, "application/pdf", PDF));
                mixed.addBodyPart(binaryPart("转发邮件.eml", Part.ATTACHMENT, "message/rfc822", innerEml("被转发主题")));
                mixed.addBodyPart(binaryPart(null, Part.ATTACHMENT, "message/rfc822", innerEml("无名副本")));
            }
        }

        MimeMessage m = new MimeMessage(session);
        if (subj == Subject.NORMAL) m.setSubject("Q3 Meeting Notes");
        else if (subj == Subject.ENCODED) m.setSubject("季度会议纪要（10月）");
        m.setFrom(new jakarta.mail.internet.InternetAddress("sender@ext.test"));
        m.setContent(mixed);
        m.saveChanges();
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        m.writeTo(wire);
        return new MimeMessage(session, new ByteArrayInputStream(wire.toByteArray()));
    }

    @TestFactory
    List<DynamicTest> subjectBodyAttachmentMatrix() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Subject s : Subject.values())
            for (Body b : Body.values())
                for (Att a : Att.values()) {
                    String name = "subject=" + s + ",body=" + b + ",att=" + a;
                    Executable ex = () -> {
                        MimeMessage m = build(s, b, a);
                        MailContentParser.ParsedBody parsed = MailContentParser.parse(m.getContent());

                        int expectedAtt = switch (a) { case NONE -> 0; case ONE -> 1; case MANY -> 3; };
                        assertEquals(expectedAtt, parsed.attachments.size(), name + " 附件数量");

                        int expectedInline = b == Body.RELATED ? 1 : 0;
                        assertEquals(expectedInline, parsed.inline.size(), name + " 内联图片数量");

                        // 附件字节完整、有名字
                        for (var at : parsed.attachments) {
                            assertNotNull(at.data, name + " 附件字节非空");
                            assertTrue(at.data.length > 0, name + " 附件非零字节");
                            assertNotNull(at.fileName, name + " 附件有名");
                            assertFalse(at.fileName.isBlank(), name + " 附件名非空白");
                        }

                        // 正文与附件互不污染
                        boolean bodyExpected = b != Body.NONE;
                        boolean hasBody = (parsed.html != null && !parsed.html.isBlank())
                                || (parsed.text != null && !parsed.text.isBlank());
                        assertEquals(bodyExpected, hasBody, name + " 正文存在性");

                        // 主题解码
                        String decoded = MailHeaderDecoder.decodeSubject(m);
                        switch (s) {
                            case NORMAL -> assertEquals("Q3 Meeting Notes", decoded, name + " 普通主题");
                            case ENCODED -> assertEquals("季度会议纪要（10月）", decoded, name + " 编码主题");
                            case NONE -> assertEquals("", decoded, name + " 无主题应为空串");
                        }
                    };
                    tests.add(DynamicTest.dynamicTest(name, ex));
                }
        return tests;
    }

    @Test
    void manyAttachmentsIncludeNamedAndNamelessEml() throws Exception {
        MailContentParser.ParsedBody p = MailContentParser.parse(build(Subject.ENCODED, Body.HTML, Att.MANY).getContent());
        assertEquals(3, p.attachments.size());
        assertTrue(p.attachments.stream().anyMatch(a -> "报告.pdf".equals(a.fileName)
                && a.mimeType.startsWith("application/pdf")));
        assertTrue(p.attachments.stream().anyMatch(a -> "转发邮件.eml".equals(a.fileName)
                && a.mimeType.contains("message/rfc822")));
        // 无名 .eml 兜底命名，且仍带 .eml 后缀
        var noName = p.attachments.stream().filter(a -> a.fileName.startsWith("forwarded-message-")).findFirst();
        assertTrue(noName.isPresent(), "无文件名的 .eml 应有兜底名");
        assertTrue(noName.get().fileName.endsWith(".eml"));
    }

    @Test
    void unnamedBinaryPartCapturedAsAttachmentNotBody() throws Exception {
        // 无文件名、无显式 disposition 的二进制部件：应作附件，不能污染正文
        MimeMultipart mixed = new MimeMultipart("mixed");
        mixed.addBodyPart(textPart("text/plain", "这是真正的正文"));
        MimeBodyPart bin = new MimeBodyPart();
        bin.setDataHandler(new jakarta.activation.DataHandler(new ByteArrayDataSource(PDF, "application/octet-stream")));
        // 不设置文件名与 disposition
        mixed.addBodyPart(bin);

        MimeMessage m = new MimeMessage(session);
        m.setSubject("二进制无附件头");
        m.setContent(mixed);
        m.saveChanges();
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        m.writeTo(wire);
        MimeMessage re = new MimeMessage(session, new ByteArrayInputStream(wire.toByteArray()));

        MailContentParser.ParsedBody p = MailContentParser.parse(re.getContent());
        assertEquals(1, p.attachments.size(), "无名二进制应作 1 个附件");
        assertTrue(p.attachments.get(0).fileName.startsWith("attachment-"));
        assertEquals("这是真正的正文", p.text, "正文不应被二进制污染");
    }

    @Test
    void inlineImageNeverAppearsAsAttachment() throws Exception {
        MailContentParser.ParsedBody p = MailContentParser.parse(build(Subject.NORMAL, Body.RELATED, Att.ONE).getContent());
        assertEquals(1, p.inline.size(), "内联图归 inline");
        assertEquals(1, p.attachments.size(), "内联图不计入附件，仅 pdf");
        assertEquals("报告.pdf", p.attachments.get(0).fileName);
        // 内联图字节走 inline，不落到 attachments
        assertTrue(p.attachments.stream().noneMatch(a -> a.mimeType.startsWith("image/")));
    }

    @Test
    void chineseAttachmentNameIsDecoded() throws Exception {
        MimeMessage re = build(Subject.NORMAL, Body.NONE, Att.ONE);
        MailContentParser.ParsedBody p = MailContentParser.parse(re.getContent());
        assertEquals("报告.pdf", p.attachments.get(0).fileName, "RFC2047 中文附件名应解码");
    }
}
