package com.mail.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 用户级设置。
 */
@Getter
@Setter
@Entity
@Table(name = "user_settings")
@EntityListeners(AuditingEntityListener.class)
public class UserSetting {

    @Id
    private Long userId;

    @Column(name = "default_sync_interval_sec", nullable = false)
    private Integer defaultSyncIntervalSec = 60;

    /** 执行记录保存范围：ALL 全部 / SUCCESS 只留成功 / FAILED 只留失败 / NONE 不保留 */
    @Column(name = "run_retention_scope", nullable = false, length = 16)
    private String runRetentionScope = "ALL";

    /** 执行记录保存天数，超期清理 */
    @Column(name = "run_retention_days", nullable = false)
    private Integer runRetentionDays = 7;

    /** 外观主题模式：auto 跟随系统 / light 浅色 / dark 深色 */
    @Column(name = "theme", nullable = false, length = 16)
    private String theme = "auto";

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
