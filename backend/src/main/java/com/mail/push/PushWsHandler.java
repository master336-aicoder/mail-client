package com.mail.push;

import com.mail.auth.JwtService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.Map;

/**
 * WebSocket 处理器：握手时从 query 参数 token 鉴权，并注册到 PushService。
 * 连接地址：ws://host/api/ws?token=xxx
 */
@Slf4j
@RequiredArgsConstructor
public class PushWsHandler extends TextWebSocketHandler {

    private final JwtService jwtService;
    private final PushService pushService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        URI uri = session.getUri();
        String token = null;
        if (uri != null) {
            String q = uri.getQuery();
            if (q != null) {
                for (String pair : q.split("&")) {
                    String[] kv = pair.split("=", 2);
                    if (kv.length == 2 && "token".equals(kv[0])) token = kv[1];
                }
            }
        }
        Long userId = resolve(token);
        if (userId == null) {
            try {
                session.close(CloseStatus.POLICY_VIOLATION);
            } catch (Exception ignored) {
            }
            return;
        }
        pushService.register(userId, session);
    }

    private Long resolve(String token) {
        if (token == null) return null;
        try {
            Claims c = jwtService.parse(token);
            return c.get("uid", Long.class);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // 客户端消息当前仅用于心跳回执，可忽略
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = pushService.findUserId(session);
        if (userId != null) {
            pushService.unregister(userId, session);
        }
    }
}