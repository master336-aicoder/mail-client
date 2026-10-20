package com.mail.notification.dto;

import com.mail.notification.domain.Notification;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通知列表项。
 */
@Data
public class NotificationItem {

    private Long id;
    private String type;
    private String title;
    private String content;
    private String link;
    private boolean read;
    private LocalDateTime createdAt;

    public static NotificationItem of(Notification n) {
        NotificationItem i = new NotificationItem();
        i.setId(n.getId());
        i.setType(n.getType());
        i.setTitle(n.getTitle());
        i.setContent(n.getContent());
        i.setLink(n.getLink());
        i.setRead(n.isRead());
        i.setCreatedAt(n.getCreatedAt());
        return i;
    }
}
