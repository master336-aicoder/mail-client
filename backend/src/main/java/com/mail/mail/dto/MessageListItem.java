package com.mail.mail.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 邮件列表项。
 */
@Data
public class MessageListItem {
    private Long id;
    private Long folderId;
    private String subject;
    private String sender;
    private String senderEmail;
    private LocalDateTime date;
    private Integer flags;
    private boolean read;
    private boolean hasAttach;
    private String preview;
    private Long size;

    @Data
    public static class Page {
        private long total;
        private List<MessageListItem> list;
    }
}