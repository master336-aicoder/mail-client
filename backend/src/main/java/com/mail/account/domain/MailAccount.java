package com.mail.account.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 用户的邮箱账户（外部邮箱服务绑定）。
 */
@Getter
@Setter
@Entity
@Table(name = "mail_accounts")
@EntityListeners(AuditingEntityListener.class)
public class MailAccount {

    /** 收信排程：按间隔轮询 */
    public static final String SYNC_INTERVAL = "INTERVAL";
    /** 收信排程：每天定点 */
    public static final String SYNC_DAILY = "DAILY";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(name = "display_name", length = 128)
    private String displayName;

    @Column(nullable = false, length = 10)
    private String protocol = "IMAP";

    @Column(nullable = false, length = 255)
    private String host;

    @Column(nullable = false)
    private Integer port;

    @Column(nullable = false, length = 16)
    private String encryption = "SSL";

    @Column(name = "auth_type", nullable = false, length = 16)
    private String authType = "PASSWORD";

    @Column(nullable = false, length = 255)
    private String username;

    @Column(name = "secret_encrypted", length = 2048)
    @JsonIgnore
    private String secretEncrypted;

    @Column(name = "smtp_host", length = 255)
    private String smtpHost;

    @Column(name = "smtp_port")
    private Integer smtpPort;

    @Column(name = "smtp_encryption", length = 16)
    private String smtpEncryption = "STARTTLS";

    @Column(name = "is_active", nullable = false, columnDefinition = "tinyint(1)")
    private boolean active = true;

    @Column(name = "sync_interval_sec")
    private Integer syncIntervalSec;

    /** 收信排程方式：INTERVAL=按间隔轮询，DAILY=每天定点。 */
    @Column(name = "sync_mode", nullable = false, length = 16)
    private String syncMode = SYNC_INTERVAL;

    /** DAILY 模式下的每日执行时刻（服务器本地时区）。 */
    @Column(name = "sync_daily_time")
    private LocalTime syncDailyTime;

    /** 忽略 TLS 证书校验错误（自签名/域名不匹配/已过期），收信与发信共用。 */
    @Column(name = "ignore_cert_error", nullable = false, columnDefinition = "tinyint(1)")
    private boolean ignoreCertError = false;

    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}