package com.mail.account.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 账户更新请求。字段为 null 表示保持原值不变；secret 为空同样表示不修改已存密码。
 */
@Data
public class AccountUpdateRequest {

    @Email(message = "邮箱格式不正确")
    private String email;

    private String displayName;

    @Pattern(regexp = "(?i)IMAP|POP3", message = "收件协议仅支持 IMAP / POP3")
    private String protocol;

    private String host;

    @Min(value = 1, message = "端口范围 1-65535")
    @Max(value = 65535, message = "端口范围 1-65535")
    private Integer port;

    @Pattern(regexp = "(?i)SSL|STARTTLS|NONE", message = "收信加密方式仅支持 SSL / STARTTLS / NONE")
    private String encryption;

    @Pattern(regexp = "(?i)SSL|STARTTLS|NONE", message = "发信加密方式仅支持 SSL / STARTTLS / NONE")
    private String smtpEncryption;

    private String username;

    /** 非空则重新加密覆盖；空或 null 保持原密码不变。 */
    private String secret;

    private String smtpHost;

    @Min(value = 1, message = "端口范围 1-65535")
    @Max(value = 65535, message = "端口范围 1-65535")
    private Integer smtpPort;

    private Integer syncIntervalSec;

    /** 收信排程方式：INTERVAL=按间隔轮询，DAILY=每天定点；null 表示不变。 */
    @Pattern(regexp = "(?i)INTERVAL|DAILY", message = "收信排程仅支持「按间隔」或「每天定点」")
    private String syncMode;

    /** DAILY 模式的每日执行时刻，前端按 HH:mm 提交。 */
    @Pattern(regexp = "([01]\\d|2[0-3]):[0-5]\\d(:[0-5]\\d)?", message = "每日收信时刻格式应为 HH:mm，如 08:30")
    private String syncDailyTime;

    private Boolean active;

    /** null 表示保持原值；true 表示忽略证书校验错误（收信与发信同时生效）。 */
    private Boolean ignoreCertError;
}
