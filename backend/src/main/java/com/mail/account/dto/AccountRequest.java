package com.mail.account.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 添加账户请求。
 */
@Data
public class AccountRequest {

    @NotBlank(message = "邮箱不能为空")
    @Email
    private String email;

    private String displayName;

    @NotBlank
    private String protocol = "IMAP";

    @NotBlank
    private String host;

    private Integer port = 993;

    private String encryption = "SSL";

    private String authType = "PASSWORD";

    private String username;

    /** 密码 / OAuth 授权码，服务端加密存储。 */
    private String secret;

    private String smtpHost;
    private Integer smtpPort;
    private String smtpEncryption = "STARTTLS";
}