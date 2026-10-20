package com.mail.protocol;

import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 复现：显示名内含逗号且被双引号包裹的 From/To（RFC 822 合法），
 * 验证 InternetAddress 是否解析成「单个」地址而非按逗号切碎的残片。
 */
class AddressCommaInQuotedNameTest {

    private static final String EML =
            "From: \"Zhang,Dong(RI/BJ)\" <dong.zhang8@gientech.com>\r\n" +
            "To: \"Zhang,Dong(RI/BJ)\" <dong.zhang8@gientech.com>\r\n" +
            "Cc: \"Li,Si\" <li.si@example.com>, \"Wang,Wu\" <wang@example.com>\r\n" +
            "Subject: =?utf-8?q?=E5=AE=A2=E6=88=B7=E7=AB=AF=E6=B5=8B=E8=AF=95?=\r\n" +
            "MIME-Version: 1.0\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "\r\n" +
            "body\r\n";

    private MimeMessage parse() throws Exception {
        Session s = Session.getInstance(new Properties());
        return new MimeMessage(s, new ByteArrayInputStream(EML.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void fromYieldsOneCorrectAddress() throws Exception {
        jakarta.mail.Address[] from = parse().getFrom();
        assertEquals(1, from.length, "From 应为单个地址，不能被逗号切碎");
        InternetAddress ia = (InternetAddress) from[0];
        assertEquals("dong.zhang8@gientech.com", ia.getAddress());
        assertEquals("Zhang,Dong(RI/BJ)", ia.getPersonal());
    }

    @Test
    void ccYieldsTwoAddresses() throws Exception {
        var cc = parse().getRecipients(jakarta.mail.Message.RecipientType.CC);
        assertEquals(2, cc.length);
        assertEquals("li.si@example.com", ((InternetAddress) cc[0]).getAddress());
        assertEquals("Li,Si", ((InternetAddress) cc[0]).getPersonal());
    }

    @Test
    void addressToStringKeepsQuotedNameIntact() throws Exception {
        var to = parse().getRecipients(jakarta.mail.Message.RecipientType.TO);
        assertEquals(1, to.length);
        // 后端 joinAddresses 逐条取 Address.toString() 再以逗号拼接，
        // 单地址场景下即等于 InternetAddress 规范化后的完整串，逗号必须留在引号内。
        assertEquals("\"Zhang,Dong(RI/BJ)\" <dong.zhang8@gientech.com>", to[0].toString());
    }
}
