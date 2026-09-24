package com.mail.auth;

import com.mail.auth.dto.LoginRequest;
import com.mail.auth.dto.RegisterRequest;
import com.mail.common.ApiResponse;
import com.mail.common.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final LoginThrottle throttle;

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        String ip = clientIp(http);
        throttle.guard("login", ip + "|" + req.getUsername());
        try {
            Map<String, Object> result = authService.login(req.getUsername(), req.getPassword());
            throttle.reset("login", ip + "|" + req.getUsername());
            return ApiResponse.ok(result);
        } catch (BizException e) {
            // 认证失败已计入限流（guard 已在进入时累加）
            throw e;
        }
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