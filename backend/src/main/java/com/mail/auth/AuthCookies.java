package com.mail.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

/**
 * 鉴权相关的 Cookie 常量与读写助手。
 *
 * <ul>
 *   <li>{@link #AUTH} —— HttpOnly 的会话 JWT，JS 不可读，防 XSS 窃取令牌。</li>
 *   <li>{@link #CSRF} —— 非 HttpOnly 的随机串，前端读出并回放到 {@code X-CSRF-Token} 头，构成双重提交 CSRF 防护。</li>
 * </ul>
 */
public final class AuthCookies {

    public static final String AUTH = "access_token";
    public static final String CSRF = "csrf_token";
    public static final String CSRF_HEADER = "X-CSRF-Token";

    private AuthCookies() {
    }

    /** 写入鉴权 + CSRF 两个 Cookie；SameSite=Lax、Path=/、Max-Age 与 JWT 生命周期一致。 */
    public static void write(HttpServletResponse res, String token, String csrf, long maxAgeSec, boolean secure) {
        res.addHeader(HttpHeaders.SET_COOKIE, build(AUTH, token, maxAgeSec, secure, true).toString());
        res.addHeader(HttpHeaders.SET_COOKIE, build(CSRF, csrf, maxAgeSec, secure, false).toString());
    }

    public static void clear(HttpServletResponse res, boolean secure) {
        res.addHeader(HttpHeaders.SET_COOKIE, build(AUTH, "", 0, secure, true).toString());
        res.addHeader(HttpHeaders.SET_COOKIE, build(CSRF, "", 0, secure, false).toString());
    }

    private static ResponseCookie build(String name, String value, long maxAgeSec, boolean secure, boolean httpOnly) {
        return ResponseCookie.from(name, value == null ? "" : value)
                .httpOnly(httpOnly)
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAgeSec)
                .build();
    }

    public static String read(HttpServletRequest req, String name) {
        Cookie[] cs = req.getCookies();
        if (cs == null) return null;
        for (Cookie c : cs) {
            if (name.equals(c.getName())) return c.getValue();
        }
        return null;
    }
}
