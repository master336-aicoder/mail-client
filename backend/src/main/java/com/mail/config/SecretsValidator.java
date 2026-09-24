package com.mail.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动期密钥健康校验：拒绝使用仓库内置的公开默认密钥。
 *
 * <p>生产环境必须通过环境变量 {@code JWT_SECRET}/{@code MASTER_KEY} 注入真实密钥，
 * 否则应用直接启动失败（fail-fast），避免伪造令牌 / 解密已存邮箱凭据等严重风险。
 * 本地开发可在 {@code dev} profile（{@code mail.app.allow-insecure-defaults=true}）显式放行。
 */
@Component
public class SecretsValidator {

    private static final Logger log = LoggerFactory.getLogger(SecretsValidator.class);

    /** 与 application.yml 中历史内置值一致，视为公开、不可用于生产。 */
    private static final String DEFAULT_JWT =
            "mail-client-dev-secret-change-me-0123456789abcdef0123456789abcdef";
    private static final String DEFAULT_MASTER =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final List<String> DEFAULT_MINIO = List.of("admin", "admin123456");

    private final AppProperties app;
    private final MinioProperties minio;

    public SecretsValidator(AppProperties app, MinioProperties minio) {
        this.app = app;
        this.minio = minio;
    }

    @PostConstruct
    void validate() {
        boolean jwtInsecure = isBlank(app.getJwtSecret()) || DEFAULT_JWT.equals(app.getJwtSecret());
        boolean masterInsecure = isBlank(app.getMasterKey()) || DEFAULT_MASTER.equals(app.getMasterKey());
        // 仅在确实启用 MinIO 存储时才校验其凭据，避免 local 存储被无关默认值误拦
        boolean minioInsecure = "minio".equalsIgnoreCase(app.getStorageMode())
                && DEFAULT_MINIO.get(0).equals(minio.getAccessKey())
                && DEFAULT_MINIO.get(1).equals(minio.getSecretKey());

        if (!jwtInsecure && !masterInsecure && !minioInsecure) {
            return;
        }

        if (app.isAllowInsecureDefaults()) {
            log.warn("!!! 安全警告：检测到仓库内置默认密钥（{}），仅允许用于本地开发。"
                            + " 生产环境请通过环境变量 JWT_SECRET / MASTER_KEY 注入真实密钥并关闭 allow-insecure-defaults。",
                    joinInsecure(jwtInsecure, masterInsecure, minioInsecure));
            return;
        }

        throw new IllegalStateException(
                "启动被拒绝：检测到不安全的默认密钥 -> " + joinInsecure(jwtInsecure, masterInsecure, minioInsecure)
                        + "。生产环境必须通过环境变量 JWT_SECRET / MASTER_KEY（及 MinIO 凭据）注入真实密钥。"
                        + " 若确为本地开发，可激活 profile 'dev' 或设置 mail.app.allow-insecure-defaults=true。");
    }

    private static String joinInsecure(boolean jwt, boolean master, boolean minio) {
        StringBuilder sb = new StringBuilder();
        if (jwt) sb.append("jwt-secret, ");
        if (master) sb.append("master-key, ");
        if (minio) sb.append("minio 默认凭据, ");
        return sb.toString().replaceAll(", $", "");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
