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
import java.time.LocalTime;

/**
 * 后台任务定义（不是执行记录）。
 *
 * <p>账户同步是「每个账户一条」的周期性任务（periodic=true，由调度器按 nextRunAt 反复触发）；
 * PST 导入导出是「每次手动操作一条」的一次性任务（periodic=false）。
 * 每次执行写入 {@link TaskRun}，产物写入 {@link TaskAttachment}。
 */
@Getter
@Setter
@Entity
@Table(name = "tasks")
@EntityListeners(AuditingEntityListener.class)
public class Task {

    public static final String TYPE_ACCOUNT_SYNC = "account_sync";
    public static final String TYPE_PST_EXPORT = "pst_export";

    /** 排程方式：按间隔轮询 */
    public static final String SCHEDULE_INTERVAL = "INTERVAL";
    /** 排程方式：每天定点 */
    public static final String SCHEDULE_DAILY = "DAILY";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    /** 任务类型，对应 {@link com.mail.task.TaskHandler#type()} */
    @Column(name = "type", nullable = false, length = 32)
    private String type;

    /** 参考账户 */
    @Column(name = "account_id")
    private Long accountId;

    /** 任务参数（JSON），一次性任务用它记住本次操作的输入，如导出的文件夹列表。 */
    @Column(columnDefinition = "TEXT")
    private String params;

    /** 是否周期执行 */
    @Column(nullable = false)
    private boolean periodic = false;

    /** 周期任务是否继续调度；账户停用时同步置为 false */
    @Column(nullable = false)
    private boolean enabled = true;

    /** 周期秒数，为空表示跟随用户级默认收取频率 */
    @Column(name = "schedule_sec")
    private Integer scheduleSec;

    /** 排程方式：INTERVAL=按间隔轮询，DAILY=每天定点。 */
    @Column(name = "schedule_mode", nullable = false, length = 16)
    private String scheduleMode = SCHEDULE_INTERVAL;

    /** DAILY 模式下的每日执行时刻（服务器本地时区）。 */
    @Column(name = "daily_time")
    private LocalTime dailyTime;

    /**
     * 任务级执行记录保存范围：ALL / SUCCESS / FAILED / NONE。
     * 为空表示跟随用户全局策略；非空时优先级高于全局。
     */
    @Column(name = "run_retention_scope", length = 16)
    private String runRetentionScope;

    /** 任务级保存天数；为空表示跟随全局。与 {@link #runRetentionScope} 一样，非空即覆盖全局。 */
    @Column(name = "run_retention_days")
    private Integer runRetentionDays;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    /** 周期任务的下次执行时间；一次性任务为空 */
    @Column(name = "next_run_at")
    private LocalDateTime nextRunAt;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
