package com.mail.auth;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 解析 {@code Authorization: Bearer <token>}，写入 CurrentUser 与 Spring Security 上下文。
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // 公开端点不做 CSRF 校验（登录/注册/登出时用户可能尚无 csrf_token Cookie）
        return path.equals("/api/auth/login")
                || path.equals("/api/auth/register")
                || path.equals("/api/auth/logout");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        String token = null;
        boolean fromCookie = false;
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            token = header.substring(7);
        } else {
            token = AuthCookies.read(request, AuthCookies.AUTH);
            fromCookie = StringUtils.hasText(token);
        }

        if (StringUtils.hasText(token)) {
            try {
                Claims claims = jwtService.parse(token);
                Long uid = claims.get("uid", Long.class);
                if (uid != null) {
                    // 经 Cookie 携带凭证的非安全方法，需通过双重提交 CSRF 校验
                    if (fromCookie && isUnsafeMethod(request.getMethod()) && !csrfOk(request)) {
                        rejectCsrf(response);
                        return;
                    }
                    CurrentUser.set(uid);
                    String role = claims.get("role", String.class);
                    if (role == null || role.isBlank()) {
                        role = "USER";
                    }
                    String username = claims.get("username", String.class);
                    List<SimpleGrantedAuthority> authorities =
                            List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()));
                    UsernamePasswordAuthenticationToken auth =
                            new UsernamePasswordAuthenticationToken(
                                    username != null ? username : String.valueOf(uid),
                                    null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            } catch (Exception ignored) {
                // 无效 token 视为匿名
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            CurrentUser.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private static boolean isUnsafeMethod(String m) {
        return !("GET".equalsIgnoreCase(m) || "HEAD".equalsIgnoreCase(m)
                || "OPTIONS".equalsIgnoreCase(m) || "TRACE".equalsIgnoreCase(m));
    }

    /** 请求头 X-CSRF-Token 必须与 csrf_token Cookie 一致（双重提交）。 */
    private static boolean csrfOk(HttpServletRequest request) {
        String cookieVal = AuthCookies.read(request, AuthCookies.CSRF);
        String headerVal = request.getHeader(AuthCookies.CSRF_HEADER);
        return StringUtils.hasText(cookieVal) && cookieVal.equals(headerVal);
    }

    private static void rejectCsrf(HttpServletResponse response) throws IOException {
        response.setStatus(403);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":40301,\"message\":\"CSRF 校验失败\",\"data\":null}");
    }
}