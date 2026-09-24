-- 邮件收取频率：账户级同步间隔 + 用户级默认同步间隔
ALTER TABLE mail_accounts
  ADD COLUMN sync_interval_sec INT NULL,
  ADD COLUMN last_synced_at DATETIME NULL;

-- 用户级设置（默认同步频率等）
CREATE TABLE user_settings (
  user_id                    BIGINT PRIMARY KEY AUTO_INCREMENT,
  default_sync_interval_sec  INT NOT NULL DEFAULT 60,
  created_at                 DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at                 DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_settings_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB;