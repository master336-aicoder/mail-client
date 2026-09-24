package com.mail.protocol;

import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 邮件正文解析：从 String / MimeMultipart / 嵌套 Message 中提取 HTML 与纯文本正文，
 * 同时收集内嵌资源（Content-ID 指向的正文图片）。
 */
public final class MailContentParser {

    private MailContentParser() {
    }

    public static class ParsedBody {
        public String html;
        public String text;
        /** 内嵌资源（Content-ID → 字节），供后续持久化与 cid 重写。 */
        public final List<com.mail.mail.domain.PendingInlinePart> inline = new ArrayList<>();
    }

    /**
     * 解析邮件正文，尽量返回 HTML 与纯文本两种形态。
     */
    public static ParsedBody parse(Object content) {
        ParsedBody body = new ParsedBody();
        if (content == null) return body;
        try {
            if (content instanceof String s) {
                body.text = s;
                body.html = s;
            } else if (content instanceof InputStream in) {
                String raw = readFully(in);
                body.text = raw;
                body.html = raw;
            } else if (content instanceof Multipart mp) {
                extractMultipart(mp, body);
            } else if (content instanceof Message nested) {
                ParsedBody sub = parse(nested.getContent());
                body.html = sub.html;
                body.text = sub.text;
            } else if (content instanceof Part p) {
                Object c = p.getContent();
                if (c instanceof String s) {
                    body.text = s;
                    body.html = s;
                } else if (c instanceof InputStream in) {
                    String raw = readFully(in);
                    body.text = raw;
                    body.html = raw;
                } else if (c instanceof Multipart mp) {
                    extractMultipart(mp, body);
                }
            }
        } catch (Exception e) {
            // 解析异常不影响其余字段
        }
        // 纯文本为空时，从 HTML 推导纯文本；HTML 为空时反推
        if ((body.text == null || body.text.isBlank()) && (body.html != null && !body.html.isBlank())) {
            body.text = org.jsoup.Jsoup.parse(body.html).text();
        }
        if ((body.html == null || body.html.isBlank()) && (body.text != null && !body.text.isBlank())) {
            body.html = "<p>" + body.text.replaceAll("\\R", "<br/>") + "</p>";
        }
        return body;
    }

    private static void extractMultipart(Multipart mp, ParsedBody body) throws Exception {
        for (int i = 0; i < mp.getCount(); i++) {
            BodyPart part = mp.getBodyPart(i);
            Object c = part.getContent();
            if (c instanceof Multipart inner) {
                extractMultipart(inner, body);
                continue;
            }
            String type = part.getContentType() == null ? "" : part.getContentType().toLowerCase();
            String disposition = part.getDisposition() == null ? "" : part.getDisposition().toLowerCase();
            String contentId = part.getHeader("Content-ID") == null ? null : part.getHeader("Content-ID")[0];
            // 内嵌资源：带 Content-ID 且非附件下载，如正文图片
            boolean isInline = contentId != null && !contentId.isBlank()
                    && !disposition.contains("attachment");
            if (isInline) {
                byte[] data = readBytes(part);
                if (data != null && data.length > 0 && isImageMime(type)) {
                    String cid = contentId.trim();
                    if (cid.startsWith("<")) cid = cid.substring(1);
                    if (cid.endsWith(">")) cid = cid.substring(0, cid.length() - 1);
                    String fileName = fileNameOf(part, i);
                    String mime = mimeOf(type);
                    body.inline.add(new com.mail.mail.domain.PendingInlinePart(cid, fileName, mime, data));
                    continue;
                }
            }
            String text = null;
            if (c instanceof String s) {
                text = s;
            } else if (c instanceof Message nested) {
                ParsedBody sub = parse(nested.getContent());
                if (sub.html != null && body.html == null) body.html = sub.html;
                if (sub.text != null && body.text == null) body.text = sub.text;
                continue;
            }
            if (text == null) continue;
            if (type.contains("html") && body.html == null) {
                body.html = text;
            } else if (type.contains("plain") && body.text == null) {
                body.text = text;
            }
        }
    }

    private static boolean isImageMime(String type) {
        String t = type.toLowerCase();
        return t.startsWith("image/");
    }

    private static String fileNameOf(BodyPart part, int i) {
        String name = null;
        try {
            name = part.getFileName();
        } catch (Exception ignored) {
        }
        if (name != null && !name.isBlank()) return stripQuotes(name);
        String cid = null;
        try {
            String[] h = part.getHeader("Content-ID");
            if (h != null) cid = h[0];
        } catch (Exception ignored) {
        }
        if (cid != null && !cid.isBlank()) {
            String s = cid.trim().replaceAll("[<>]", "");
            return "inline-" + i + "-" + s + ".bin";
        }
        return "inline-" + i + ".bin";
    }

    private static String mimeOf(String type) {
        int semi = type.indexOf(';');
        return semi > 0 ? type.substring(0, semi).trim() : type.trim();
    }

    private static String stripQuotes(String s) {
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
        return s;
    }

    private static byte[] readBytes(Part part) {
        if (part == null) return null;
        try (InputStream in = part.getInputStream()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static String readFully(InputStream in) {
        if (in == null) return "";
        try (InputStream is = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
