package com.mail.notification;

import com.mail.auth.CurrentUser;
import com.mail.common.ApiResponse;
import com.mail.common.PageResult;
import com.mail.notification.dto.NotificationIdsRequest;
import com.mail.notification.dto.NotificationItem;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 消息通知（站内信）接口：列表、未读数、标记已读、删除。
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /** 消息列表：可选只看未读、按类型筛选、按标题/正文关键字搜索。 */
    @GetMapping
    public ApiResponse<PageResult<NotificationItem>> list(@RequestParam(defaultValue = "1") int page,
                                                          @RequestParam(defaultValue = "20") int size,
                                                          @RequestParam(defaultValue = "false") boolean unreadOnly,
                                                          @RequestParam(required = false) String type,
                                                          @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(notificationService.page(CurrentUser.requireId(), unreadOnly, type, keyword, page, size));
    }

    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Object>> unreadCount() {
        return ApiResponse.ok(Map.of("count", notificationService.unreadCount(CurrentUser.requireId())));
    }

    @PutMapping("/{id}/read")
    public ApiResponse<Void> markRead(@PathVariable Long id) {
        notificationService.markRead(CurrentUser.requireId(), id);
        return ApiResponse.ok();
    }

    @PutMapping("/read-all")
    public ApiResponse<Map<String, Object>> markAllRead() {
        return ApiResponse.ok(Map.of("updated", notificationService.markAllRead(CurrentUser.requireId())));
    }

    @PostMapping("/batch-delete")
    public ApiResponse<Map<String, Object>> batchDelete(@Valid @RequestBody NotificationIdsRequest req) {
        return ApiResponse.ok(Map.of("deleted", notificationService.delete(CurrentUser.requireId(), req.getIds())));
    }

    @DeleteMapping("/read")
    public ApiResponse<Map<String, Object>> clearRead() {
        return ApiResponse.ok(Map.of("deleted", notificationService.clearRead(CurrentUser.requireId())));
    }
}
