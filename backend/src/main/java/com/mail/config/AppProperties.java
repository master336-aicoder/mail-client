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
    /** 允许绑定指向内网/回环的邮件服务器主机（SSRF 防护开关，仅限本地测试放行）。 */
    private boolean allowPrivateHost = false;
    /** 鉴权 Cookie 是否加 Secure 属性。生产（HTTPS）应为 true；本地 http 开发为 false。 */
    private boolean authCookieSecure = false;
    private String storageMode = "local";
    private String ostBaseDir = "./data/ost";
    private String attachDir = "./data/attachments";
    private String pstExportDir = "./data/exports";
    /** 收信时把每封邮件的原始 MIME 流转存到此目录，便于离线反复解析排查（仅调试用）。 */
    private String rawDir = "./data/raw";
    /** 是否转存原始 MIME 流。生产默认关闭；本地 dev 打开以便反复解析。 */
    private boolean saveRawMime = false;
    /** 是否开放 /api/dev/parse-eml 调试端点：上传/读取 .eml 反复解析，查看各要素兜底结果。 */
    private boolean emlParseDebug = false;
    /**
     * 是否启用周期任务调度（{@code @EnableScheduling}），与启动类上的
     * {@code @ConditionalOnProperty(name="mail.app.scheduling-enabled", matchIfMissing=true)} 对应。
     * {@code --mail.app.scheduling-enabled=false} 时进程照常提供 HTTP 服务，但周期收信、
     * 记录保存策略清理、系统资源预警等 @Scheduled 任务都不运行，只可手动触发。默认开启。
     */
    private boolean schedulingEnabled = true;
    private int pstMaxImportMb = 100;
    private String corsAllowedOrigins = "http://localhost:5173,http://127.0.0.1:5173";
}