-- =====================================================================
-- v9 消息通知（站内信）
--
-- 新邮件到达、收信失败、发信失败、账户删除、文件夹清空等事件的持久化提醒；
-- 在线时经 WebSocket 实时推送（NOTIFICATION 事件），离线登录后在铃铛里查看。
-- =====================================================================

CREATE TABLE notifications (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id    BIGINT       NOT NULL,
  type       VARCHAR(32)  NOT NULL,
  title      VARCHAR(128) NOT NULL,
  content    VARCHAR(1000),
  link       VARCHAR(255),
  is_read    TINYINT(1)   NOT NULL DEFAULT 0,
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_notif_user_read (user_id, is_read, id),
  INDEX idx_notif_user_created (user_id, created_at)
) ENGINE = InnoDB;
