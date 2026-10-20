package com.mail.protocol;

import jakarta.mail.internet.MimeUtility;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 邮件头部解码工具：处理 RFC 2047 编码词、常见字符集别名（GB2312/GBK/Big5 等）、
 * 以及可能的双重编码问题（UTF-8 → Latin-1 → UTF-8）。
 *
 * 解码顺序：
 * 1. 优先用 Jakarta Mail 的 MimeUtility.decodeText；
 * 2. 若返回仍含 "=?...?=" 或替换字符，则手动解析编码词并按更宽松的字符集解码；
 * 3. 最后再走一遍双重编码检测与修复。
 */
@Slf4j
public final class MailHeaderDecoder {

    private MailHeaderDecoder() {
    }

    /** 兼容编码词的宽松匹配：允许字符集缺失、编码方式小写、无 closing ?=。 */
    private static final Pattern ENCODED_WORD = Pattern.compile(
            "=\\?([^?\\s]*)\\?([BbQq])\\?([^?]*)\\?=");

    public static String decodeSubject(jakarta.mail.Message m) {
        try {
            String[] headers = m.getHeader("Subject");
            if (headers != null && headers.length > 0) {
                String raw = headers[0];
                return postProcess(decode(raw));
            }
            String subject = m.getSubject();
            if (subject == null) return "";
            return postProcess(subject);
        } catch (Exception e) {
            log.debug("解码标题失败: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 通用文本解码：用于发件人姓名等非 Subject 头的场景。
     */
    public static String decodeText(String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        return postProcess(decode(text));
    }

    /**
     * 完整解码：先 MimeUtility，再手动编码词解析。
     */
    private static String decode(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String mimeOut = null;
        try {
            mimeOut = MimeUtility.decodeText(raw);
        } catch (Exception e) {
            log.debug("MimeUtility 解码失败: {}", e.getMessage());
        }
        // MimeUtility 结果看起来仍像有未解码的编码词，或包含替换字符：走手动路径
        if (mimeOut == null || mimeOut.contains("=?") || containsReplacementChar(mimeOut)) {
            String manual = manualDecode(raw);
            if (manual != null && !manual.isEmpty()) return manual;
        }
        return mimeOut == null ? raw : mimeOut;
    }

    private static String manualDecode(String raw) {
        Matcher mt = ENCODED_WORD.matcher(raw);
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        int last = 0;
        while (mt.find()) {
            sb.append(raw, last, mt.start());
            String charset = mt.group(1);
            String enc = mt.group(2);
            String payload = mt.group(3);
            try {
                byte[] bytes = "B".equalsIgnoreCase(enc)
                        ? Base64.getMimeDecoder().decode(payload)
                        : decodeQp(payload);
                String piece = decodeBytes(bytes, charset);
                sb.append(piece);
                any = true;
            } catch (Exception e) {
                // 无法解码则保留原样
                sb.append(mt.group());
            }
            last = mt.end();
        }
        sb.append(raw.substring(last));
        return any ? sb.toString() : null;
    }

    private static byte[] decodeQp(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '=' && i + 2 < s.length()) {
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) | lo);
                    i += 2;
                    continue;
                }
            }
            if (c == '_') {
                out.write(' ');
            } else {
                out.write(c);
            }
        }
        return out.toByteArray();
    }

    /**
     * 按字符集严格解码；失败时按 GB18030 → UTF-8 依次回退，兼容常见的字符集错标。
     */
    private static String decodeBytes(byte[] bytes, String declared) {
        String name = normalizeCharset(declared);
        // 1. 按声明字符集严格解码
        String out = tryDecode(bytes, name, true);
        if (out != null) return out;
        // 2. 中文邮箱常见误标（GBK 写成 GB2312、Big5、x-unknown 等）：GB18030 是超集
        out = tryDecode(bytes, "GB18030", true);
        if (out != null) return out;
        // 3. UTF-8 严格
        out = tryDecode(bytes, "UTF-8", true);
        if (out != null) return out;
        // 4. 最后按声明字符集宽松解码，尽量出内容
        return tryDecode(bytes, name, false);
    }

    private static String tryDecode(byte[] bytes, String charsetName, boolean strict) {
        Charset cs;
        try {
            cs = Charset.forName(charsetName);
        } catch (Exception e) {
            cs = StandardCharsets.UTF_8;
        }
        CharsetDecoder dec = cs.newDecoder();
        if (strict) {
            dec.onMalformedInput(CodingErrorAction.REPORT);
            dec.onUnmappableCharacter(CodingErrorAction.REPORT);
            try {
                return dec.decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException e) {
                return null;
            }
        } else {
            dec.onMalformedInput(CodingErrorAction.REPLACE);
            dec.onUnmappableCharacter(CodingErrorAction.REPLACE);
            try {
                return dec.decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException e) {
                return null;
            }
        }
    }

    /** 常见字符集别名与错标的规范化。保留 ISO-8859-1 因为它是合法声明。 */
    private static String normalizeCharset(String raw) {
        if (raw == null || raw.isEmpty()) return "UTF-8";
        String c = raw.trim().toLowerCase();
        switch (c) {
            case "gb2312":
            case "gb_2312":
            case "gb_2312-80":
            case "euc-cn":
            case "x-chinese-cns":
            case "windows-936":
            case "cp936":
            case "gbk":
                return "GB18030";
            case "ks_c_5601-1987":
            case "ksc_5601":
            case "euc-kr":
            case "cp949":
                return "EUC-KR";
            case "big-5":
            case "cn-big5":
            case "big5-hkscs":
            case "cp950":
                return "Big5";
            case "sjis":
            case "shift_jis":
            case "ms932":
            case "cp932":
                return "Shift_JIS";
            case "utf8":
            case "utf-8":
            case "x-utf-8":
                return "UTF-8";
            case "default":
            case "system":
            case "":
                return "GB18030";
            default:
                return c;
        }
    }

    private static boolean containsReplacementChar(String s) {
        return s.indexOf('\uFFFD') >= 0;
    }

    /** 后处理：修复双重编码。 */
    private static String postProcess(String s) {
        if (s == null) return "";
        return fixIfDoubleEncoded(s);
    }

    private static String fixIfDoubleEncoded(String s) {
        if (isDoubleEncodedUtf8(s)) {
            String fixed = fixDoubleEncodedUtf8(s);
            // 修复后仍含替换字符说明猜错，保留原文
            if (fixed != null && !fixed.contains("\uFFFD")) return fixed;
        }
        return s;
    }

    /**
     * 检测字符串是否为二次编码的 UTF-8（UTF-8 字节被当作 Latin-1 解释后再编码为 UTF-8）。
     * 特征：包含大量 U+0080-U+00FF 范围的字符（Latin-1 高位区），且按 Latin-1 反解回 UTF-8 后能成立。
     */
    private static boolean isDoubleEncodedUtf8(String s) {
        if (s == null || s.isEmpty()) return false;
        int highLatinCount = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x0080 && c <= 0x00FF) {
                highLatinCount++;
            }
        }
        if (highLatinCount == 0) return false;
        double ratio = (double) highLatinCount / s.length();
        if (ratio <= 0.3) return false;
        // 只有能严格解码回 UTF-8 才认为是二次编码，避免误伤合法 Latin-1（法语、西语等）
        byte[] bytes = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) bytes[i] = (byte) (s.charAt(i) & 0xFF);
        String probe = tryDecode(bytes, "UTF-8", true);
        return probe != null;
    }

    private static String fixDoubleEncodedUtf8(String s) {
        try {
            byte[] bytes = new byte[s.length()];
            for (int i = 0; i < s.length(); i++) {
                bytes[i] = (byte) (s.charAt(i) & 0xFF);
            }
            String fixed = new String(bytes, StandardCharsets.UTF_8);
            if (fixed.contains("\uFFFD")) return s;
            return fixed;
        } catch (Exception e) {
            log.debug("修复二次编码失败: {}", e.getMessage());
            return s;
        }
    }
}
