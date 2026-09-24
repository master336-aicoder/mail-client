package com.mail.account.dto;

import lombok.Data;

/**
 * 账户更新请求（同步频率 / 启用状态）。
 */
@Data
public class AccountUpdateRequest {
    private Integer syncIntervalSec;
    private Boolean active;
}