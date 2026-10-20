package com.mail.account.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 添加账户请求。
 */
@Data
public class AccountRequest {

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确，请填写如 name@example.com 的完整地址")
    private String email;

    private String displayName;

    @NotBlank(message = "请选择收信协议（IMAP / POP3）")
    private String protocol = "IMAP";

    @NotBlank(message = "收件服务器不能为空")
    private String host;

    @jakarta.validation.constraints.Min(value = 1, message = "收件端口范围 1-65535")
    @jakarta.validation.constraints.Max(value = 65535, message = "收件端口范围 1-65535")
    private Integer port = 993;

    // 大小写不敏感：手写 API / 旧数据用 ssl、starttls 也应通过，落库前统一转大写
    @Pattern(regexp = "(?i)SSL|STARTTLS|NONE", message = "收信加密方式仅支持 SSL / STARTTLS / NONE")
    private String encryption = "SSL";

    private String authType = "PASSWORD";

    private String username;

    /** 密码 / OAuth 授权码，服务端加密存储。 */
    private String secret;

    private String smtpHost;
    private Integer smtpPort;

    @Pattern(regexp = "(?i)SSL|STARTTLS|NONE", message = "发信加密方式仅支持 SSL / STARTTLS / NONE")
    private String smtpEncryption = "STARTTLS";

    /** 接受此证书 / 添加安全例外：忽略 TLS 证书校验错误（自签名、域名不匹配、已过期）。 */
    private Boolean ignoreCertError = false;
}