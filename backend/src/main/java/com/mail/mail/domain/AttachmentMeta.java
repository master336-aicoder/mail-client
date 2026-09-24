package com.mail.mail.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 附件元数据（内容存放在本地或 MinIO）。
 */
@Getter
@Setter
@Entity
@Table(name = "attachment_meta")
@EntityListeners(AuditingEntityListener.class)
public class AttachmentMeta {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "message_id")
    private Long messageId;

    @Column(name = "account_id")
    private Long accountId;

    @Column(nullable = false, length = 512)
    private String name;

    @Column(name = "mime_type", length = 255)
    private String mimeType;

    @Column(nullable = false)
    private Long size;

    @Column(name = "storage_ref", length = 1024)
    private String storageRef;

    @Column(name = "storage_type", nullable = false, length = 16)
    private String storageType = "local";

    /** 内嵌资源的 Content-ID（不含尖括号），用于 `cid:` 引用重写；普通附件为 null。 */
    @Column(name = "content_id", length = 255)
    private String contentId;

    /** 是否为内嵌资源（正文内图片等）。 */
    @Column(name = "disposition", length = 16)
    private String disposition;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}