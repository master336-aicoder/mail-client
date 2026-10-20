package com.mail.account.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 连接测试请求：字段同「添加账户」，额外允许带上已保存账户的 id，
 * 以便密码留空时复用该账户已存的密码来测试未保存的配置改动。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AccountTestRequest extends AccountRequest {

    private Long accountId;
}
