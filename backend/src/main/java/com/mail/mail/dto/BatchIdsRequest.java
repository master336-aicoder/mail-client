package com.mail.mail.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 仅携带邮件 id 的批量请求（批量删除）。
 */
@Data
public class BatchIdsRequest {

    @NotNull(message = "请选择要操作的邮件")
    @Size(max = 500, message = "单次批量操作不能超过 500 条")
    private List<Long> ids;
}
