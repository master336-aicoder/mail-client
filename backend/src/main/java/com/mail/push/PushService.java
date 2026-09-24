package com.mail.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 推送中心：维护 userId → 会话集合，向在线用户实时推送事件。
 * 事件类型：MAIL_NEW / FOLDER_UPDATE / FLAGS_CHANGE / RULE_TRIGGERED / SYNC_STATUS
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PushService {

    private final ObjectMapper objectMapper;
    private final Map<Long, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    /**
     * 会话建立时注册。
     */
    public void register(Long userId, WebSocketSession session) {
        sessions.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(session);
        log.info("WS 上线: user={} total={}", userId, sessions.get(userId).size());
    }

    public void unregister(Long userId, WebSocketSession session) {
        Set<WebSocketSession> set = sessions.get(userId);
        if (set != null) {
            set.remove(session);
            if (set.isEmpty()) sessions.remove(userId);
        }
    }

    /**
     * 根据会话查找归属用户，用于关闭连接时清理。
     */
    public Long findUserId(WebSocketSession session) {
        Optional<Long> found = sessions.entrySet().stream()
                .filter(e -> e.getValue().contains(session))
                .map(Map.Entry::getKey)
                .findFirst();
        return found.orElse(null);
    }

    /**
     * 向用户推送事件。
     */
    public void publish(Long userId, String type, Object payload) {
        Set<WebSocketSession> set = sessions.get(userId);
        if (set == null || set.isEmpty()) return;
        try {
            String json = objectMapper.writeValueAsString(Map.of("type", type, "data", payload));
            for (WebSocketSession s : set) {
                if (s.isOpen()) {
                    s.sendMessage(new TextMessage(json));
                }
            }
        } catch (IOException e) {
            log.warn("WS 推送失败: {}", e.getMessage());
        }
    }
}