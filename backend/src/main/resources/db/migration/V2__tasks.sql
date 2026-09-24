-- =====================================================================
-- v2 后台任务表
-- =====================================================================

CREATE TABLE tasks (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id     BIGINT       NOT NULL,
  name        VARCHAR(128) NOT NULL,
  type        VARCHAR(32)  NOT NULL,
  status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
  progress    INT          NOT NULL DEFAULT 0,
  started_at  DATETIME,
  finished_at DATETIME,
  duration_ms BIGINT,
  account_id  BIGINT,
  message     VARCHAR(500),
  error       VARCHAR(1000)
) ENGINE=InnoDB;

CREATE INDEX idx_tasks_user_id ON tasks (user_id);
CREATE INDEX idx_tasks_status ON tasks (status);