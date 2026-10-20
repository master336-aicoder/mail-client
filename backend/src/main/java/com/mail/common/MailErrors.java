package com.mail.common;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 JavaMail / JDK 网络与 TLS 异常翻译成用户能看懂、且带补救动作的中文提示。
 * <p>面向接口的错误信息必须中文优先：原始英文只作为兜底附在「服务器返回：」之后，
 * 避免把 {@code Could not connect to host} 这类堆栈式文本直接丢给用户。
 */
public final class MailErrors {

    private MailErrors() {
    }

    /** 匹配服务器回复中的冷却时长，如 {@code retry in 292s}。 */
    private static final Pattern RETRY_AFTER =
            Pattern.compile("retry(?:ing)? in (\\d+)\\s*s(?:ec(?:ond)?s?)?\\b");

    /** 翻译异常，不带端点信息。 */
    public static String explain(Throwable t) {
        return explain(t, null);
    }

    /**
     * 翻译异常。{@code target} 为本次操作的端点描述（如 {@code IMAP 127.0.0.1:143}），
     * 会拼在提示前，便于用户对照账户配置定位是哪一侧、哪个端口出错。
     */
    public static String explain(Throwable t, String target) {
        String chain = chainOf(t);
        String hint = hintFor(chain, t);
        return target == null || target.isBlank() ? hint : hint + "（" + target + "）";
    }

    /** 沿 cause 链收集类名与消息，统一小写后用于关键字判定。 */
    private static String chainOf(Throwable t) {
        StringBuilder sb = new StringBuilder();
        int guard = 0;
        for (Throwable c = t; c != null && guard++ < 12; c = c.getCause()) {
            sb.append(c.getClass().getName()).append(' ')
              .append(c.getMessage()).append('\n');
            if (c.getCause() == c) break;
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static String hintFor(String s, Throwable t) {
        String raw = brief(t);
        // 已经是中文的提示（多为本类在上层翻译后包出来的 BizException），原样透传，
        // 否则会得出「操作失败，服务器返回：认证失败：…」这种双重前缀。
        // 顶层消息优先：协议异常现在会作为 cause 一并带上，brief() 取到的是英文原文，
        // 而顶层那份是上层已经挑好措辞的中文提示（如「收件人被服务器拒绝：xxx」）。
        String top = t.getMessage();
        if (containsHan(top)) return top;
        if (containsHan(raw)) return raw;
        // 内部空指针一类的缺陷：原始信息（Cannot invoke "..." because "x" is null）对用户毫无意义，
        // 也会泄露实现细节，统一换成可行动提示，细节由日志保留。
        if (t instanceof NullPointerException || s.contains("nullpointerexception")
                || s.contains("cannot invoke") || s.contains("is null") || s.contains("index out of bounds")) {
            return "服务端处理该请求时出错：请检查填写内容后重试，若反复出现请联系管理员（详情见服务端日志）";
        }
        // 下面三类都会被 JavaMail 包成 AuthenticationFailedException，但根本原因与密码无关，
        // 必须排在「认证失败：用户名或密码不正确」之前，否则会把用户引去反复改授权码。
        if (s.contains("requires an encrypted connection") || s.contains("use stls") || s.contains("stls or port")
                || s.contains("must issue stls") || s.contains("logindisabled") || s.contains("requires tls")
                || (s.contains("plaintext") && s.contains("login"))
                || (s.contains("unencrypted") && (s.contains("auth") || s.contains("login")))
                || (s.contains("secure connection") && s.contains("auth"))) {
            return "服务器要求在加密连接上认证，当前「不加密」的连接被拒绝：这与用户名、密码无关，无需修改密码。"
                    + "请把加密方式改为 SSL（POP3 端口 995 / IMAP 端口 993），或保留原端口并改用 STARTTLS";
        }
        // JavaMail 有两种措辞：IMAP「STARTTLS required but not supported」/ SMTP「STARTTLS is required but host does not support STARTTLS」
        if (s.contains("starttls") && (s.contains("not support") || s.contains("unsupported"))) {
            return "该服务器/端口不支持 STARTTLS：请把加密方式改为「无加密」，或改用 SSL 端口（IMAP 993 / POP3 995 / SMTP 465）";
        }
        if (s.contains("too many failed authentication") || s.contains("too many authentication")
                || s.contains("authentication attempts") || retrySeconds(s) != null) {
            Integer wait = retrySeconds(s);
            return "服务商已临时限制认证尝试"
                    + (wait == null ? "" : "，约 " + wait + " 秒后自动解除")
                    + "：这不是密码错误，请停止反复测试，等待限制解除后再试一次。"
                    + "收信与发信共用同一计数，期间两侧都会失败；反复重试只会不断延长锁定时长";
        }
        // 认证：最常见，也最容易被误认为「密码错了」——多数邮箱要求应用专用密码/授权码。
        if (s.contains("authenticationfailedexception") || s.contains("authentication failed")
                || s.contains("login failed") || s.contains("invalid credentials")
                || s.contains("535") || s.contains("authenticationsasl")) {
            return "认证失败：用户名或密码不正确。请确认用户名为邮箱全称，并使用服务商提供的「授权码 / 应用专用密码」"
                    + "（QQ、163、Gmail 及多数企业邮箱不接受登录密码）";
        }
        if (s.contains("unknownhostexception") || s.contains("host not found") || s.contains("unknown host")) {
            return "无法解析服务器域名：请检查主机名拼写，以及本机 DNS / 外网是否可达";
        }
        if (s.contains("sslhandshakeexception") || s.contains("no appropriate protocol")
                || s.contains("unrecognized ssl message") || s.contains("ssl message")
                || (s.contains("could not convert socket to tls"))) {
            return "TLS 握手失败：该端口很可能不是 SSL(隐式 TLS) 端口，请改用 STARTTLS，或换成 SSL 端口"
                    + "（IMAP 993 / POP3 995 / SMTP 465）；若服务器使用自签名证书，可勾选「接受此证书（添加安全例外）」";
        }
        if (s.contains("certificate") || s.contains("pkix path") || s.contains("peer unverified")
                || s.contains("hostname in repository") || s.contains("cert")) {
            return "服务器证书校验未通过（证书无效、已过期或与域名不匹配）：请确认网络环境，或联系邮箱管理员更新证书"
                    + "；若为内网/自签名证书，可在账户设置中勾选「接受此证书（添加安全例外）」后重试";
        }
        if (s.contains("sockettimeoutexception") || s.contains("read timed out") || s.contains("connect timeout")
                || s.contains("connection timed out")) {
            return "连接超时：服务器无响应，请检查网络 / 防火墙，或稍后重试";
        }
        if (s.contains("connection refused") || s.contains("connectexception") || s.contains("connect failed")
                || s.contains("could not connect") || s.contains("connection timed out")
                || s.contains("noroutetohostexception")) {
            return "无法建立连接：请核对服务器地址与端口，并确认该端口已开启对应协议"
                    + "（IMAP SSL 993 / STARTTLS 143，POP3 SSL 995，SMTP SSL 465 / STARTTLS 587）";
        }
        if (s.contains("relay access denied") || s.contains("relay not permitted") || s.contains("550 5.7.1")) {
            return "服务器拒绝转发：发件人必须与登录账号一致，或需在邮箱后台先开启 SMTP 发信服务";
        }
        if (s.contains("foldernotfoundexception") || s.contains("no such folder") || s.contains("doesn't exist")
                || s.contains("does not exist")) {
            return "服务器上不存在该邮箱目录：请确认目录名称与权限";
        }
        if (s.contains("protocol exception") && s.contains("bad protocol command")) {
            return "协议命令被服务器拒绝：请确认收信协议（IMAP / POP3）选择正确，POP3 端口不支持 IMAP 连接";
        }
        if (s.contains("dataintegrityviolationexception") || s.contains("duplicate entry")
                || s.contains("constraintviolationexception")) {
            return "数据冲突或字段不合法：" + brief(t);
        }
        // 请求体解析失败（非法 JSON / 编码错误）常被包成 IOException，不能算「网络不可达」
        if (s.contains("utf-8") || s.contains("utf8") || s.contains("json") || s.contains("not readable")
                || s.contains("deserialization") || s.contains("parse error")) {
            return "提交的内容无法解析：请确认表单数据完整后重试（若粘贴了特殊字符，请清空后重新输入）";
        }
        if (isNetworkish(t)) {
            return "网络不可达：" + raw;
        }
        return raw.isEmpty() ? "操作失败，请稍后重试" : "操作失败，服务器返回：" + raw;
    }

    /** 认证是否被服务器拒绝（含限流与要求加密两类）：这类失败重复重试只会加重服务商限流。 */
    public static boolean isAuthRejected(Throwable t) {
        String s = chainOf(t);
        return s.contains("authenticationfailedexception") || s.contains("authentication failed")
                || s.contains("login failed") || s.contains("invalid credentials")
                || s.contains("authenticationsasl") || s.contains("535")
                || s.contains("too many failed authentication")
                // 上层已翻译过的 BizException（cause 链里可能只剩中文）也要认出来
                || (s.contains("认证失败") && s.contains("密码不正确"))
                || s.contains("临时限制认证尝试") || s.contains("在加密连接上认证");
    }

    /** 服务器回复里的认证冷却秒数（{@code retry in 292s}），没给则返回 null。 */
    public static Integer retryAfterSeconds(Throwable t) {
        return retrySeconds(chainOf(t));
    }

    /** 文本中是否含汉字：用于识别「已经是中文的用户提示」，避免二次包装。 */
    private static boolean containsHan(String s) {
        if (s == null) return false;
        return s.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
    }

    /** 从服务器回复中提取「retry in 292s」这类冷却秒数，没有则返回 null。 */
    private static Integer retrySeconds(String s) {
        Matcher m = RETRY_AFTER.matcher(s);
        if (!m.find()) return null;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isNetworkish(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof IOException) return true;
        }
        return false;
    }

    /** 取最有信息量的一层原始消息（优先最深层 cause），截断避免超长堆栈污染界面。 */
    private static String brief(Throwable t) {
        Throwable deepest = t;
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c.getMessage() != null && !c.getMessage().isBlank()) deepest = c;
            if (c.getCause() == c) break;
        }
        String m = deepest.getMessage();
        if (m == null || m.isBlank()) m = deepest.getClass().getSimpleName();
        m = m.replaceAll("\\s+", " ").trim();
        return m.length() > 160 ? m.substring(0, 160) + "…" : m;
    }
}
