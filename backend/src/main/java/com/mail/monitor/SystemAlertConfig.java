package com.mail.monitor;

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
 * 系统状态预警配置（单机部署，全表仅一行 id=1）。
 * CPU/内存/磁盘任一使用率超过各自阈值即向全体管理员发送 SYSTEM_ALERT 站内信，
 * 同类告警在 cooldown_min 分钟内不重复发送。
 */
@Getter
@Setter
@Entity
@Table(name = "system_alert_config")
@EntityListeners(AuditingEntityListener.class)
public class SystemAlertConfig {

    @Id
    private Long id = 1L;

    /** 是否开启预警巡检 */
    @Column(name = "enabled", nullable = false)
    private boolean enabled = false;

    @Column(name = "cpu_threshold_pct", nullable = false)
    private int cpuThresholdPct = 85;

    @Column(name = "mem_threshold_pct", nullable = false)
    private int memThresholdPct = 85;

    @Column(name = "disk_threshold_pct", nullable = false)
    private int diskThresholdPct = 90;

    /** 巡检间隔分钟；Scheduler 轮询但仅当距上次巡检超过该间隔才实际采样判定 */
    @Column(name = "check_interval_min", nullable = false)
    private int checkIntervalMin = 5;

    /** 同一指标两次告警的最小间隔分钟 */
    @Column(name = "cooldown_min", nullable = false)
    private int cooldownMin = 30;

    @Column(name = "last_cpu_alert_at")
    private LocalDateTime lastCpuAlertAt;

    @Column(name = "last_mem_alert_at")
    private LocalDateTime lastMemAlertAt;

    @Column(name = "last_disk_alert_at")
    private LocalDateTime lastDiskAlertAt;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
