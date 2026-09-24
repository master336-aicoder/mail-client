package com.mail.admin;

import com.mail.account.domain.MailAccount;
import com.mail.account.domain.MailAccountRepository;
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
    public List<Map<String, Object>> users() {
        return userRepository.findAll().stream().map(u -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("email", u.getEmail());
            m.put("role", u.getRole());
            return m;
        }).toList();
    }

    @PutMapping("/users/{id}/role")
    public Map<String, Object> setRole(@PathVariable Long id, @org.springframework.web.bind.annotation.RequestBody Map<String, Object> body) {
        User u = userRepository.findById(id).orElseThrow();
        u.setRole(String.valueOf(body.getOrDefault("role", "USER")));
        userRepository.save(u);
        return Map.of("id", u.getId(), "role", u.getRole());
    }

    @GetMapping("/accounts")
    public List<MailAccount> accounts() {
        return accountRepository.findAll();
    }
}