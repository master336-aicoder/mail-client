package com.mail.notification.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 通知批量删除请求。
 */
@Data
public class NotificationIdsRequest {

    @NotNull(message = "请选择要删除的消息")
    @Size(max = 500, message = "单次批量操作不能超过 500 条")
    private List<Long> ids;
}
