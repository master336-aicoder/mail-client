package com.mail.task.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 后台任务批量操作请求。
 */
@Data
public class TaskBatchRequest {

    @NotEmpty(message = "请选择要操作的任务")
    private List<Long> ids;
}
