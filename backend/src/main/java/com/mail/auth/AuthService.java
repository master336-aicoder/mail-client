package com.mail.auth;

import com.mail.common.BizException;
import com.mail.user.domain.User;
import com.mail.user.domain.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 认证：注册、登录、当前用户信息。
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Transactional
    public Map<String, Object> register(String username, String password, String email) {
        if (userRepository.existsByUsername(username)) {
            throw new BizException(40002, "用户名已存在");
        }
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setEmail(email);
        user.setRole("USER");
        user = userRepository.save(user);
        return Map.of("id", user.getId(), "username", user.getUsername());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> login(String username, String password) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BizException(40101, "用户名或密码错误"));
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BizException(40101, "用户名或密码错误");
        }
        String token = jwtService.issue(user.getId(), user.getUsername(), user.getRole());
        return Map.of(
                "accessToken", token,
                "expiresIn", 3600,
                "user", Map.of("id", user.getId(), "username", user.getUsername(), "role", user.getRole())
        );
    }

    @Transactional
    public void changePassword(String oldPassword, String newPassword) {
        Long uid = CurrentUser.requireId();
        User user = userRepository.findById(uid)
                .orElseThrow(() -> new BizException(40101, "用户不存在"));
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new BizException(40010, "原密码不正确");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> myInfo() {
        Long uid = CurrentUser.requireId();
        User user = userRepository.findById(uid)
                .orElseThrow(() -> new BizException(40101, "用户不存在"));
        return Map.of(
                "id", user.getId(),
                "username", user.getUsername(),
                "email", user.getEmail() == null ? "" : user.getEmail(),
                "role", user.getRole()
        );
    }
}