package com.mail.task.dto;

import lombok.Data;

import java.util.List;

/**
 * 执行记录批量操作请求：ids 为选中的记录；taskId 非空表示作用于该任务的全部执行记录。
 */
@Data
public class TaskRunBatchRequest {

    private List<Long> ids;

    private Long taskId;
}
