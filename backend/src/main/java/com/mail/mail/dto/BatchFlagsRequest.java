package com.mail.mail.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 批量邮件操作请求：ids 为选中的邮件，read/starred/important 为空表示该项不变。
 */
@Getter
@Setter
public class BatchFlagsRequest extends UpdateFlagsRequest {

    @NotNull(message = "请选择要操作的邮件")
    @Size(max = 500, message = "单次批量操作不能超过 500 条")
    private List<Long> ids;
}
