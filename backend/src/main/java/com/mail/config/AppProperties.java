package com.mail.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * {@code mail.app.*} 应用配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "mail.app")
public class AppProperties {

    private String jwtSecret;
    private long jwtExpireSeconds = 3600;
    private String masterKey;
    /** 允许使用仓库内置的开发默认密钥（仅限本地开发 profile，生产必须为 false 并通过环境变量注入真实密钥）。 */
    private boolean allowInsecureDefaults = false;
    private String storageMode = "local";
    private String ostBaseDir = "./data/ost";
    private String attachDir = "./data/attachments";
    private String pstExportDir = "./data/exports";
    private int pstMaxImportMb = 100;
    private String corsAllowedOrigins = "http://localhost:5173,http://127.0.0.1:5173";
}