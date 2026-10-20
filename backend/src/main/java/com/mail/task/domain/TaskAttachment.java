package com.mail.task.domain;

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
 * 任务执行产生的附件（如导出包），供任务跑完后下载。
 */
@Getter
@Setter
@Entity
@Table(name = "task_attachments")
@EntityListeners(AuditingEntityListener.class)
public class TaskAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    /** 冗余存归属用户，下载时直接校验越权 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 255)
    private String name;

    /** 相对导出根目录的路径，下载时拼接并校验不得越出根目录 */
    @Column(nullable = false, length = 1024)
    private String path;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "content_type", length = 128)
    private String contentType;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
