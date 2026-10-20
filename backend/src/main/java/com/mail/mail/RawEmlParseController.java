package com.mail.mail;

import com.mail.auth.CurrentUser;
import com.mail.common.ApiResponse;
import com.mail.common.BizException;
import com.mail.config.AppProperties;
import com.mail.mail.domain.PendingInlinePart;
import com.mail.protocol.MailContentParser;
import com.mail.protocol.MailHeaderDecoder;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.mail.Message.RecipientType;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 调试端点：上传（或用已转存的 uid）一封 .eml 原始邮件，离线反复解析，返回各要素的兜底结果，
 * 用于在不重新连服务器的情况下反复验证主题/收件人/正文/附件的解析策略。
 *
 * <p>仅在 {@code mail.app.eml-parse-debug=true} 时开放（本地 dev），生产环境直接 403。
 */
@RestController
@RequestMapping("/api/dev/parse-eml")
@RequiredArgsConstructor
public class RawEmlParseController {

    private final AppProperties appProperties;

    @PostMapping
    public ApiResponse<Map<String, Object>> parse(@RequestParam("file") MultipartFile file) {
        if (!appProperties.isEmlParseDebug()) {
            throw new BizException(40300, "调试解析端点未开放");
        }
        CurrentUser.requireId();
        if (file == null || file.isEmpty()) throw new BizException("请上传 .eml 文件");
        try {
            byte[] raw = file.getBytes();
            Session session = Session.getInstance(new Properties());
            MimeMessage m = new MimeMessage(session, new ByteArrayInputStream(raw));
            return ApiResponse.ok(describe(m, raw.length));
        } catch (BizException be) {
            throw be;
        } catch (Exception e) {
            throw new BizException("解析失败：" + e.getMessage());
        }
    }

    /** 把一封邮件的解析结果结构化输出，正文与附件做长度/预览截断，避免响应过大。 */
    static Map<String, Object> describe(MimeMessage m, int rawSize) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rawBytes", rawSize);
        out.put("subject", safe(() -> MailHeaderDecoder.decodeSubject(m)));
        out.put("contentType", safe(m::getContentType));
        out.put("from", safe(() -> join(m.getFrom())));
        out.put("to", safe(() -> join(m.getRecipients(RecipientType.TO))));
        out.put("cc", safe(() -> join(m.getRecipients(RecipientType.CC))));
        out.put("bcc", safe(() -> join(m.getRecipients(RecipientType.BCC))));
        out.put("date", safe(() -> m.getSentDate() == null ? null :
                m.getSentDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime().toString()));

        MailContentParser.ParsedBody body = MailContentParser.parse(m.getContent());
        out.put("bodyHtmlChars", body.html == null ? 0 : body.html.length());
        out.put("bodyTextChars", body.text == null ? 0 : body.text.length());
        out.put("bodyHtmlPreview", preview(body.html));
        out.put("bodyTextPreview", preview(body.text));

        List<Map<String, Object>> inlines = new ArrayList<>();
        for (PendingInlinePart p : body.inline) inlines.add(partMap(p));
        out.put("inline", inlines);

        List<Map<String, Object>> atts = new ArrayList<>();
        for (PendingInlinePart p : body.attachments) atts.add(partMap(p));
        out.put("attachments", atts);
        return out;
    }

    private static Map<String, Object> partMap(PendingInlinePart p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.fileName);
        m.put("contentId", p.contentId);
        m.put("mimeType", p.mimeType);
        m.put("bytes", p.data == null ? 0 : p.data.length);
        return m;
    }

    private static String join(jakarta.mail.Address[] a) {
        if (a == null || a.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (jakarta.mail.Address addr : a) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(addr instanceof InternetAddress ia ? ia.getAddress() : addr.toString());
        }
        return sb.toString();
    }

    private static String preview(String s) {
        if (s == null) return null;
        return s.length() > 500 ? s.substring(0, 500) + "…" : s;
    }

    private static Object safe(java.util.concurrent.Callable<Object> c) {
        try { return c.call(); } catch (Exception e) { return "⚠ " + e.getClass().getSimpleName() + ": " + e.getMessage(); }
    }

    // 保留给后续「按 uid 读取已转存 .eml」扩展使用
    @SuppressWarnings("unused")
    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}
