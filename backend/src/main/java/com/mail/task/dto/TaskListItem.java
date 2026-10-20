package com.mail.task.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 后台任务列表项：任务定义 + 最近一次执行的状态快照。
 */
@Data
public class TaskListItem {

    private Long id;
    private String name;
    private String type;
    private Long accountId;
    private String accountEmail;

    /** 周期任务（账户同步）还是一次性任务（导入导出） */
    private boolean periodic;
    private boolean enabled;
    /** 任务自定义周期秒数，为空表示跟随用户默认频率 */
    private Integer scheduleSec;
    /** 实际生效的周期秒数；每日定点任务为空 */
    private Integer effectiveScheduleSec;
    /** 排程方式：INTERVAL / DAILY */
    private String scheduleMode;
    /** DAILY 模式的每日执行时刻 */
    private LocalTime dailyTime;

    /** 任务级记录保存策略；为空表示跟随用户全局策略 */
    private String runRetentionScope;
    private Integer runRetentionDays;

    private LocalDateTime createdAt;
    private LocalDateTime lastRunAt;
    private LocalDateTime nextRunAt;

    /** 以下均来自最近一次执行 */
    private Long lastRunId;
    private String status;
    private int progress;
    private String message;
    private String error;
    private String result;
    private String triggerType;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;
    private int attachmentCount;
}
