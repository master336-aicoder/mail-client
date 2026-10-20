package com.mail.notification;

import com.mail.auth.CurrentUser;
import com.mail.common.ApiResponse;
import com.mail.notification.dto.AnnouncementRequest;
import com.mail.user.domain.UserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 站内公告（管理员发布、用户以站内信形式接收）。
 *
 * <p>路径挂在 /api/admin 下，SecurityConfig 已要求 ROLE_ADMIN；类上再标注一次，
 * 避免将来有人调整匹配规则时把发布能力开放给普通用户。
 */
@RestController
@RequestMapping("/api/admin/announcements")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminAnnouncementController {

    private final NotificationService notificationService;
    private final UserRepository userRepository;

    @PostMapping
    public ApiResponse<Map<String, Object>> publish(@Valid @RequestBody AnnouncementRequest req) {
        Long senderId = CurrentUser.requireId();
        List<Long> targets = Boolean.TRUE.equals(req.getTargetAll())
                ? userRepository.findAll().stream().map(u -> u.getId()).toList()
                : userRepository.findAllById(req.getUserIds() == null ? List.of() : req.getUserIds())
                        .stream().map(u -> u.getId()).toList();
        int sent = notificationService.publishAnnouncement(senderId, targets,
                req.getTitle(), req.getContent(), req.getLink());
        return ApiResponse.ok(Map.of("sent", sent, "targetAll", Boolean.TRUE.equals(req.getTargetAll())));
    }

    /** 发布历史：每条公告的送达与已读人数。 */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> history(@RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(notificationService.announcements(limit));
    }
}
