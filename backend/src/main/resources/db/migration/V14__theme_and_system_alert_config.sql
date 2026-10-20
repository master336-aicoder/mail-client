-- =====================================================================
-- v14 主题偏好 + 系统状态预警配置
--
-- 1) 通用设置「外观 · 主题模式」与登录用户绑定：写入 user_settings.theme，
--    跨设备/清缓存后仍能恢复；前端仍会双写 localStorage 做首屏无闪烁。
-- 2) 系统管理「系统状态预警」阈值：单机部署，只需一行配置；由定时扫描器
--    按 check_interval_min 采样 CPU/内存/磁盘，超阈值时给全体管理员发
--    SYSTEM_ALERT 站内信，同一指标 last_triggered_at 冷却期内不重复发。
-- =====================================================================

ALTER TABLE user_settings
  ADD COLUMN theme VARCHAR(16) NOT NULL DEFAULT 'auto';

CREATE TABLE system_alert_config (
  id                    BIGINT PRIMARY KEY,
  enabled               TINYINT(1)  NOT NULL DEFAULT 0,
  cpu_threshold_pct     INT         NOT NULL DEFAULT 85,
  mem_threshold_pct     INT         NOT NULL DEFAULT 85,
  disk_threshold_pct    INT         NOT NULL DEFAULT 90,
  check_interval_min    INT         NOT NULL DEFAULT 5,
  cooldown_min          INT         NOT NULL DEFAULT 30,
  last_cpu_alert_at     DATETIME    NULL,
  last_mem_alert_at     DATETIME    NULL,
  last_disk_alert_at     DATETIME   NULL,
  created_at            DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB;

INSERT INTO system_alert_config (id, enabled) VALUES (1, 0);
