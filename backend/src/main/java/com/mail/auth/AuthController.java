package com.mail.auth;

import com.mail.auth.dto.LoginRequest;
import com.mail.auth.dto.RegisterRequest;
import com.mail.common.ApiResponse;
import com.mail.common.BizException;
import com.mail.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final LoginThrottle throttle;
    private final AppProperties props;

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginRequest req,
                                                  HttpServletRequest http, HttpServletResponse res) {
        String ip = clientIp(http);
        throttle.guard("login", ip + "|" + req.getUsername());
        Map<String, Object> result;
        try {
            result = authService.login(req.getUsername(), req.getPassword());
        } catch (BizException e) {
            // 认证失败已计入限流（guard 已在进入时累加）
            throw e;
        }
        throttle.reset("login", ip + "|" + req.getUsername());
        // 令牌经 HttpOnly Cookie 下发，响应体不再包含 accessToken
        String token = String.valueOf(result.get("accessToken"));
        AuthCookies.write(res, token, UUID.randomUUID().toString(),
                props.getJwtExpireSeconds(), props.isAuthCookieSecure());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user", result.get("user"));
        body.put("expiresIn", result.get("expiresIn"));
        return ApiResponse.ok(body);
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletResponse res) {
        AuthCookies.clear(res, props.isAuthCookieSecure());
        return ApiResponse.ok();
    }

    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(@Valid @RequestBody RegisterRequest req, HttpServletRequest http) {
        throttle.guard("register", clientIp(http));
        if (!req.getPassword().equals(req.getConfirmPassword())) {
            throw new BizException("两次密码不一致");
        }
        return ApiResponse.ok(authService.register(req.getUsername(), req.getPassword(), req.getEmail()));
    }

    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me() {
        return ApiResponse.ok(authService.myInfo());
    }

    @PostMapping("/password")
    public ApiResponse<Void> changePassword(@RequestBody Map<String, String> body) {
        String oldPassword = body.getOrDefault("oldPassword", "");
        String newPassword = body.getOrDefault("newPassword", "");
        if (newPassword == null || newPassword.length() < 6) {
            throw new BizException("新密码长度至少 6 位");
        }
        authService.changePassword(oldPassword, newPassword);
        return ApiResponse.ok();
    }

    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}