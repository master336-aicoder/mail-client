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
 * 任务的一次执行记录。状态：PENDING 未开始 / RUNNING 进行中 / COMPLETED 已完成 / FAILED 失败 / CANCELLED 已中止。
 */
@Getter
@Setter
@Entity
@Table(name = "task_runs")
@EntityListeners(AuditingEntityListener.class)
public class TaskRun {

    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    public static final String TRIGGER_MANUAL = "MANUAL";
    public static final String TRIGGER_SCHEDULED = "SCHEDULED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(nullable = false, length = 16)
    private String status = PENDING;

    /** 0-100 */
    @Column(nullable = false)
    private int progress = 0;

    @Column(length = 500)
    private String message;

    @Column(length = 1000)
    private String error;

    /** 执行结果摘要，如「共导出 128 封邮件」；产物文件见 {@link TaskAttachment}。 */
    @Column(length = 1024)
    private String result;

    /** MANUAL 手动触发 / SCHEDULED 周期调度 */
    @Column(name = "trigger_type", nullable = false, length = 16)
    private String triggerType = TRIGGER_MANUAL;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
