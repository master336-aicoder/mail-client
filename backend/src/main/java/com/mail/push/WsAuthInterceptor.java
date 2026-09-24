package com.mail.push;

import com.mail.auth.AuthCookies;
import com.mail.auth.JwtService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * WebSocket 握手鉴权：优先从 HttpOnly 会话 Cookie 解析 uid（回退 URL 查询 token），
 * 写入 session attributes 供 {@link PushWsHandler} 使用。
 */
@RequiredArgsConstructor
public class WsAuthInterceptor implements HandshakeInterceptor {

    public static final String ATTR_UID = "uid";

    private final JwtService jwtService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = null;
        if (request instanceof ServletServerHttpRequest servletRequest) {
            HttpServletRequest req = servletRequest.getServletRequest();
            Cookie[] cs = req.getCookies();
            if (cs != null) {
                for (Cookie c : cs) {
                    if (AuthCookies.AUTH.equals(c.getName())) {
                        token = c.getValue();
                        break;
                    }
                }
            }
            if (token == null || token.isBlank()) {
                token = req.getParameter("token"); // 兼容旧的 query token 方式
            }
        }
        Long uid = resolve(token);
        if (uid == null) {
            return false; // 拒绝握手
        }
        attributes.put(ATTR_UID, uid);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }

    private Long resolve(String token) {
        if (token == null || token.isBlank()) return null;
        try {
            Claims c = jwtService.parse(token);
            return c.get("uid", Long.class);
        } catch (Exception e) {
            return null;
        }
    }
}
