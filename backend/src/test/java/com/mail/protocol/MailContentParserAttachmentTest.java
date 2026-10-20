package com.mail.protocol;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 收信附件解析：验证普通附件与 message/rfc822 邮件副本（.eml）能被采集，
 * 修复“接收带 .eml 附件的邮件时附件不显示”的缺陷。
 */
class MailContentParserAttachmentTest {

    private final Session session = Session.getInstance(new Properties());

    private MimeMessage innerEml() throws Exception {
        MimeMessage inner = new MimeMessage(session);
        inner.setFrom(new jakarta.mail.internet.InternetAddress("from@inner.test"));
        inner.setRecipient(jakarta.mail.Message.RecipientType.TO,
                new jakarta.mail.internet.InternetAddress("to@inner.test"));
        inner.setSubject("内层邮件主题");
        inner.setText("inner body text");
        inner.saveChanges();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        inner.writeTo(bos);
        return new MimeMessage(session, new ByteArrayInputStream(bos.toByteArray()));
    }

    @Test
    void capturesEmlAndNormalAttachment() throws Exception {
        MimeMultipart mixed = new MimeMultipart("mixed");

        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setContent("请看附件", "text/plain; charset=utf-8");
        mixed.addBodyPart(textPart);

        MimeBodyPart pdfPart = new MimeBodyPart();
        pdfPart.setContent(new byte[]{1, 2, 3, 4}, "application/pdf");
        pdfPart.setFileName("report.pdf");
        pdfPart.setDisposition(jakarta.mail.Part.ATTACHMENT);
        mixed.addBodyPart(pdfPart);

        MimeBodyPart emlPart = new MimeBodyPart();
        MimeMessage inner = innerEml();
        String id = inner.getMessageID();
        emlPart.setContent(inner, "message/rfc822");
        emlPart.setFileName("forwarded.eml");
        emlPart.setDisposition(jakarta.mail.Part.ATTACHMENT);
        mixed.addBodyPart(emlPart);

        MimeMessage outer = new MimeMessage(session);
        outer.setSubject("带附件的来信");
        outer.setContent(mixed);
        outer.saveChanges();

        // 与真实收信一致：整封先序列化再从流解析，附件走 getInputStream 而非内存 setContent
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        outer.writeTo(wire);
        MimeMessage reread = new MimeMessage(session, new ByteArrayInputStream(wire.toByteArray()));

        MailContentParser.ParsedBody body = MailContentParser.parse(reread.getContent());

        assertNotNull(body.text);
        String names = body.attachments.stream().map(a -> a.fileName + "(" + a.mimeType + ","
                + (a.data == null ? "null" : a.data.length) + ")").collect(java.util.stream.Collectors.joining(", "));
        assertEquals(2, body.attachments.size(), "应采集到 pdf 与 .eml 两个附件，实际=[" + names + "]");

        assertTrue(body.attachments.stream().anyMatch(a -> "report.pdf".equals(a.fileName)
                && "application/pdf".equals(a.mimeType) && a.data.length == 4));

        var eml = body.attachments.stream().filter(a -> "forwarded.eml".equals(a.fileName)).findFirst().orElse(null);
        assertNotNull(eml, "应采集到 .eml 附件");
        assertTrue(eml.mimeType.contains("message/rfc822"));
        assertTrue(eml.contentId == null, "普通附件无 Content-ID");
        // 采集到的 .eml 字节应是可独立解析的完整邮件副本
        MimeMessage reparsed = new MimeMessage(session, new ByteArrayInputStream(eml.data));
        assertEquals("内层邮件主题", jakarta.mail.internet.MimeUtility.decodeText(reparsed.getSubject()));
    }

    @Test
    void noAttachmentEmailHasEmptyList() throws Exception {
        MimeMessage plain = new MimeMessage(session);
        plain.setSubject("纯文本");
        plain.setText("hello");
        plain.saveChanges();
        MailContentParser.ParsedBody body = MailContentParser.parse(plain.getContent());
        assertTrue(body.attachments.isEmpty());
        assertEquals("hello", body.text);
    }
}
