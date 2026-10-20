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
        /** 普通附件（disposition=attachment，或带文件名的 message/rfc822 邮件副本如 .eml）。 */
        public final List<com.mail.mail.domain.PendingInlinePart> attachments = new ArrayList<>();
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
            } else if (content instanceof byte[] bs) {
                String raw = decodeBytes(bs);
                body.text = raw;
                body.html = raw;
            } else if (content instanceof Multipart mp) {
                extractMultipart(mp, body);
            } else if (content instanceof Message nested) {
                // 转发的 message/rfc822：递归取内层正文，忽略外层解析失败
                try {
                    ParsedBody sub = parse(nested.getContent());
                    if (sub.html != null) body.html = sub.html;
                    if (sub.text != null) body.text = sub.text;
                    body.inline.addAll(sub.inline);
                    body.attachments.addAll(sub.attachments);
                } catch (Exception ignored) { }
            } else if (content instanceof Part p) {
                Object c = p.getContent();
                if (c instanceof String s) {
                    body.text = s;
                    body.html = s;
                } else if (c instanceof InputStream in) {
                    String raw = readFully(in);
                    body.text = raw;
                    body.html = raw;
                } else if (c instanceof byte[] bs) {
                    String raw = decodeBytes(bs);
                    body.text = raw;
                    body.html = raw;
                } else if (c instanceof Multipart mp) {
                    extractMultipart(mp, body);
                } else if (c instanceof Message nested) {
                    ParsedBody sub = parse(nested.getContent());
                    if (sub.html != null) body.html = sub.html;
                    if (sub.text != null) body.text = sub.text;
                    body.inline.addAll(sub.inline);
                    body.attachments.addAll(sub.attachments);
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

    /**
     * 字节流按内容猜测字符集：优先 UTF-8 严格解码，失败退 GB18030。
     * 用于 JavaMail 未识别 Content-Type 而直接把 payload 交回来的场景。
     */
    private static String decodeBytes(byte[] bs) {
        try {
            java.nio.charset.CharsetDecoder utf8 = java.nio.charset.StandardCharsets.UTF_8.newDecoder();
            utf8.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT);
            utf8.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            return utf8.decode(java.nio.ByteBuffer.wrap(bs)).toString();
        } catch (Exception ignore) { }
        try {
            return new String(bs, java.nio.charset.Charset.forName("GB18030"));
        } catch (Exception ignore) { }
        return new String(bs, java.nio.charset.StandardCharsets.ISO_8859_1);
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
            boolean hasCid = contentId != null && !contentId.isBlank();
            boolean isInline = hasCid && !disposition.contains("attachment");
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
                // 非图片的内嵌资源（如内嵌 css/svg/字体）不参与正文，直接跳过，避免把二进制当文本
                if (isImageMime(type) || (disposition.isEmpty() && hasCid && !type.contains("text")
                        && !type.contains("html") && !type.contains("plain"))) {
                    continue;
                }
            }
            if (isAttachmentPart(type, disposition, part, c)) {
                byte[] data = null;
                if (c instanceof Message nested) {
                    // message/rfc822 邮件副本（.eml）：重写完整 MIME，得到可独立打开的副本
                    data = writeMessageBytes(nested);
                }
                if (data == null || data.length == 0) data = readBytes(part);
                if (data != null && data.length > 0) {
                    String fileName = attachmentName(part, i, type);
                    body.attachments.add(new com.mail.mail.domain.PendingInlinePart(
                            null, fileName, mimeOf(type), data));
                }
                continue;
            }
            String text = null;
            if (c instanceof String s) {
                text = s;
            } else if (c instanceof byte[] bs) {
                text = decodeBytes(bs);
            } else if (c instanceof InputStream in) {
                text = readFully(in);
            } else if (c instanceof Message nested) {
                ParsedBody sub = parse(nested.getContent());
                if (sub.html != null && body.html == null) body.html = sub.html;
                if (sub.text != null && body.text == null) body.text = sub.text;
                body.inline.addAll(sub.inline);
                continue;
            } else {
                // Content-Type 未识别时 getContent() 常返回原始 InputStream 已被耗尽或返回 Part；
                // 兜底直接从 part 读字节再按内容猜字符集
                byte[] raw = readBytes(part);
                if (raw != null && raw.length > 0) text = decodeBytes(raw);
            }
            if (text == null || text.isEmpty()) continue;
            if (type.contains("html")) {
                if (body.html == null || body.html.isBlank()) body.html = text;
            } else if (type.contains("plain") || type.contains("text/")) {
                if (body.text == null || body.text.isBlank()) body.text = text;
            } else if (looksLikeHtml(text)) {
                if (body.html == null || body.html.isBlank()) body.html = text;
            } else {
                if (body.text == null || body.text.isBlank()) body.text = text;
            }
        }
    }

    /**
     * 判定是否为普通附件：显式 disposition=attachment；或带文件名的非正文部件
     * （二进制/图片/message/rfc822 邮件副本 .eml）；或无文件名但明显是二进制载荷
     * （application、image、audio、video、message/rfc822）——后者按附件兜底，
     * 避免把二进制读成正文乱码。内联正文与文本部件不在此列。
     */
    private static boolean isAttachmentPart(String type, String disposition, BodyPart part, Object c) {
        if (disposition.contains("attachment")) return true;
        String name = rawFileName(part);
        if (name != null) {
            return !(type.contains("text/plain") || type.contains("text/html")
                    || type.startsWith("multipart/"));
        }
        // 无文件名、无显式附件：仅在明显二进制载荷时按附件采集
        return c instanceof Message || isBinaryMime(type);
    }

    /** 明显二进制的 Content-Type：作为附件而非正文。 */
    private static boolean isBinaryMime(String type) {
        String t = type.toLowerCase();
        return t.startsWith("application/") || t.startsWith("image/")
                || t.startsWith("audio/") || t.startsWith("video/")
                || t.startsWith("font/") || t.contains("message/rfc822");
    }

    private static String rawFileName(BodyPart part) {
        try {
            String n = part.getFileName();
            if (n != null && !n.isBlank()) return n;
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 附件名：优先解码 RFC2047 文件名；message/rfc822 缺名补 .eml，其余按 MIME 兜底。 */
    private static String attachmentName(BodyPart part, int i, String type) {
        String name = rawFileName(part);
        if (name != null) return stripQuotes(MailHeaderDecoder.decodeText(name));
        if (type.contains("message/rfc822")) return "forwarded-message-" + i + ".eml";
        return "attachment-" + i + extensionFor(type);
    }

    private static String extensionFor(String type) {
        String t = type.toLowerCase();
        if (t.contains("pdf")) return ".pdf";
        if (t.contains("zip")) return ".zip";
        if (t.contains("octet-stream")) return ".bin";
        return "";
    }

    /** 把嵌套的 message/rfc822 完整重写为字节，得到可独立打开的 .eml 副本。 */
    private static byte[] writeMessageBytes(Message nested) {
        if (nested == null) return null;
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            nested.writeTo(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** 无 Content-Type 提示时，用起始标记粗略判断是否 HTML。 */
    private static boolean looksLikeHtml(String s) {        String head = s.length() > 512 ? s.substring(0, 512) : s;
        String low = head.toLowerCase();
        return low.contains("<html") || low.contains("<body") || low.contains("<div")
                || low.contains("<p>") || low.contains("<table") || (low.contains("<") && low.contains(">"));
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
        if (name != null && !name.isBlank()) {
            // 部分服务器返回 RFC 2047 编码的中文附件名（=?utf-8?B?...?=），必须解码
            return stripQuotes(MailHeaderDecoder.decodeText(name));
        }
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
