package com.mail.admin;

import com.mail.account.domain.MailAccount;
import com.mail.account.domain.MailAccountRepository;
import com.mail.common.ApiResponse;
import com.mail.user.domain.User;
import com.mail.user.domain.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理员接口（需 ROLE_ADMIN）。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final UserRepository userRepository;
    private final MailAccountRepository accountRepository;

    @GetMapping("/users")
    public ApiResponse<List<Map<String, Object>>> users() {
        return ApiResponse.ok(userRepository.findAll().stream().map(u -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("email", u.getEmail());
            m.put("role", u.getRole());
            return m;
        }).toList());
    }

    private static final java.util.Set<String> VALID_ROLES = java.util.Set.of("USER", "ADMIN");

    @PutMapping("/users/{id}/role")
    public Map<String, Object> setRole(@PathVariable Long id, @org.springframework.web.bind.annotation.RequestBody Map<String, Object> body) {
        User u = userRepository.findById(id).orElseThrow();
        String role = String.valueOf(body.getOrDefault("role", "USER")).trim().toUpperCase();
        if (!VALID_ROLES.contains(role)) {
            throw new com.mail.common.BizException("角色不合法，仅支持 USER / ADMIN");
        }
        u.setRole(role);
        userRepository.save(u);
        return Map.of("id", u.getId(), "role", u.getRole());
    }

    @GetMapping("/accounts")
    public ApiResponse<List<MailAccount>> accounts() {
        return ApiResponse.ok(accountRepository.findAll());
    }
}