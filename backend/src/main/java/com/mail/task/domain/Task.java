package com.mail.task.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 后台任务。状态：PENDING 未开始 / RUNNING 进行中 / COMPLETED 已完成 / FAILED 失败 / CANCELLED 已中止。
 */
@Getter
@Setter
@Entity
@Table(name = "tasks")
public class Task {

    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "type", nullable = false, length = 32)
    private String type;

    @Column(name = "status", nullable = false, length = 16)
    private String status = PENDING;

    /** 0-100 */
    @Column(nullable = false)
    private int progress = 0;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    /** 持续时间（毫秒），完成/失败后计算 */
    @Column(name = "duration_ms")
    private Long durationMs;

    /** 参考数据，如账户 ID */
    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "message", length = 500)
    private String message;

    @Column(name = "error", length = 1000)
    private String error;

    /** 任务参数（JSON），如导出任务的账户 id、文件夹 id 列表。 */
    @Column(columnDefinition = "TEXT")
    private String params;

    /** 任务结果，如导出文件路径，用于提供下载地址。 */
    @Column(name = "result", length = 1024)
    private String result;
}
