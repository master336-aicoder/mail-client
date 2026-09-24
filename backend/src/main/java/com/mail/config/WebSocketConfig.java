package com.mail.config;

import com.mail.auth.JwtService;
import com.mail.push.PushService;
import com.mail.push.PushWsHandler;
import com.mail.push.WsAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.Arrays;

/**
 * 注册 WebSocket 端点 /api/ws，握手期经 Cookie 鉴权并限制可接入来源。
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final JwtService jwtService;
    private final PushService pushService;
    private final AppProperties props;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        String[] origins = Arrays.stream(props.getCorsAllowedOrigins().split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
        registry.addHandler(new PushWsHandler(jwtService, pushService), "/api/ws")
                .addInterceptors(new WsAuthInterceptor(jwtService))
                .setAllowedOrigins(origins);
    }
}