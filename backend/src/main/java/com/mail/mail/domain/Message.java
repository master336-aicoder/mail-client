package com.mail.mail.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 邮件。
 */
@Getter
@Setter
@Entity
@Table(name = "messages")
@EntityListeners(AuditingEntityListener.class)
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "folder_id")
    private Long folderId;

    @Column(name = "remote_uid", length = 64)
    private String remoteUid;

    @Column(name = "message_id", length = 255)
    private String messageId;

    @Column(length = 1024)
    private String subject;

    @Column(length = 255)
    private String sender;

    @Column(name = "sender_email", length = 255)
    private String senderEmail;

    @Column(columnDefinition = "TEXT")
    private String recipients;

    @Column(columnDefinition = "TEXT")
    private String cc;

    @Column(columnDefinition = "TEXT")
    private String bcc;

    @Lob
    @Column(name = "body_html", columnDefinition = "LONGTEXT")
    private String bodyHtml;

    @Lob
    @Column(name = "body_text", columnDefinition = "LONGTEXT")
    private String bodyText;

    @Column(nullable = false)
    private Integer flags = 0;

    @Column(name = "has_attach", nullable = false, columnDefinition = "tinyint(1)")
    private boolean hasAttach;

    @Column(nullable = false)
    private Long size = 0L;

    @Column(length = 500)
    private String preview;

    @Column(name = "date")
    private LocalDateTime date;

    @Column(name = "is_draft", nullable = false, columnDefinition = "tinyint(1)")
    private boolean draft;

    @Column(nullable = false, columnDefinition = "tinyint(1)")
    private boolean deleted = false;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @jakarta.persistence.Transient
    private java.util.List<PendingInlinePart> pendingInline = new java.util.ArrayList<>();
}