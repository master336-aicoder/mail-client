package com.mail.protocol;

import com.mail.mail.domain.PendingInlinePart;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：企业网关（不返回 BODYSTRUCTURE）的邮件，getContent() 解析出空正文且附件缺失时，
 * 原始 MIME 回退读到的附件必须并入结果——即使回退正文同样为空，也不能整体丢弃回退。
 */
class ImapFallbackMergeTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void keepsAttachmentsFromFallbackWhenBothBodiesBlank() {
        MailContentParser.ParsedBody primary = new MailContentParser.ParsedBody();
        MailContentParser.ParsedBody fb = new MailContentParser.ParsedBody();
        fb.attachments.add(new PendingInlinePart(null, "report.pdf", "application/pdf", bytes("PDF")));

        ImapMailClient.mergeFallback(primary, fb);

        assertTrue(primary.attachments.isEmpty() == false, "回退里的附件必须保留");
        assertEquals(1, primary.attachments.size());
        assertEquals("report.pdf", primary.attachments.get(0).fileName);
    }

    @Test
    void fillsBodyFromFallbackWhenPrimaryBlank() {
        MailContentParser.ParsedBody primary = new MailContentParser.ParsedBody();
        MailContentParser.ParsedBody fb = new MailContentParser.ParsedBody();
        fb.text = "hello";
        fb.html = "<p>hello</p>";
        fb.attachments.add(new PendingInlinePart(null, "a.bin", "application/octet-stream", bytes("A")));

        ImapMailClient.mergeFallback(primary, fb);

        assertEquals("hello", primary.text);
        assertEquals("<p>hello</p>", primary.html);
        assertEquals(1, primary.attachments.size());
    }

    @Test
    void keepsPrimaryBodyAndUnionsAttachments() {
        MailContentParser.ParsedBody primary = new MailContentParser.ParsedBody();
        primary.text = "main body";
        primary.attachments.add(new PendingInlinePart(null, "one.pdf", "application/pdf", bytes("1")));

        MailContentParser.ParsedBody fb = new MailContentParser.ParsedBody();
        fb.attachments.add(new PendingInlinePart(null, "two.pdf", "application/pdf", bytes("2")));

        ImapMailClient.mergeFallback(primary, fb);

        assertEquals("main body", primary.text);
        assertEquals(2, primary.attachments.size());
    }

    @Test
    void nullFallbackIsNoOp() {
        MailContentParser.ParsedBody primary = new MailContentParser.ParsedBody();
        primary.text = "keep";
        ImapMailClient.mergeFallback(primary, null);
        assertEquals("keep", primary.text);
    }
}
