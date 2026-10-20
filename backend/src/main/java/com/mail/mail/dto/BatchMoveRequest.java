package com.mail.mail.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 批量移动邮件到指定文件夹。
 */
@Data
public class BatchMoveRequest {

    @NotNull(message = "请选择要移动的邮件")
    @Size(max = 500, message = "单次批量操作不能超过 500 条")
    private List<Long> ids;

    @NotNull(message = "请选择目标文件夹")
    private Long folderId;
}
