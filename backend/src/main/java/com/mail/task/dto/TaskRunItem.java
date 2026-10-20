package com.mail.task.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务的一次执行记录，含产物附件。
 */
@Data
public class TaskRunItem {

    private Long id;
    private Long taskId;
    private String status;
    private int progress;
    private String message;
    private String error;
    private String result;
    /** MANUAL 手动触发 / SCHEDULED 周期调度 */
    private String triggerType;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;
    private LocalDateTime createdAt;
    private List<AttachmentItem> attachments;
}
