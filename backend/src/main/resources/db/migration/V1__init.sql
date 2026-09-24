-- =====================================================================
-- v1 初始 schema
-- 依据《详细设计文档》3.1 DDL 精简落地：业务元数据入 MySQL。
-- =====================================================================

-- 用户表
CREATE TABLE users (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  username      VARCHAR(64)  NOT NULL UNIQUE,
  password_hash VARCHAR(100) NOT NULL,
  email         VARCHAR(255),
  mfa_enabled   TINYINT      NOT NULL DEFAULT 0,
  role          VARCHAR(16)  NOT NULL DEFAULT 'USER',
  storage_quota BIGINT       NOT NULL DEFAULT 1073741824,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- 邮箱账户
CREATE TABLE mail_accounts (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id         BIGINT       NOT NULL,
  email           VARCHAR(255) NOT NULL,
  display_name    VARCHAR(128),
  protocol        VARCHAR(10)  NOT NULL DEFAULT 'IMAP',
  host            VARCHAR(255) NOT NULL,
  port            INT          NOT NULL,
  encryption      VARCHAR(16)  NOT NULL DEFAULT 'SSL',
  auth_type       VARCHAR(16)  NOT NULL DEFAULT 'PASSWORD',
  username        VARCHAR(255) NOT NULL,
  secret_encrypted VARCHAR(2048),
  smtp_host       VARCHAR(255),
  smtp_port       INT,
  smtp_encryption VARCHAR(16)  DEFAULT 'STARTTLS',
  is_active       TINYINT      NOT NULL DEFAULT 1,
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_user (user_id),
  CONSTRAINT fk_acct_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB;

-- 文件夹
CREATE TABLE folders (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id    BIGINT       NOT NULL,
  account_id BIGINT,
  name       VARCHAR(128) NOT NULL,
  parent_id  BIGINT,
  seq        INT          NOT NULL DEFAULT 0,
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_folder_user (user_id),
  CONSTRAINT fk_folder_parent FOREIGN KEY (parent_id) REFERENCES folders(id)
) ENGINE=InnoDB;

-- 邮件
CREATE TABLE messages (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id      BIGINT       NOT NULL,
  account_id   BIGINT,
  folder_id    BIGINT,
  remote_uid   VARCHAR(64),
  message_id   VARCHAR(255),
  subject      VARCHAR(1024),
  sender       VARCHAR(255),
  sender_email VARCHAR(255),
  recipients   TEXT,
  cc           TEXT,
  bcc          TEXT,
  body_html    LONGTEXT,
  body_text    LONGTEXT,
  flags        INT          NOT NULL DEFAULT 0,
  has_attach   TINYINT      NOT NULL DEFAULT 0,
  size         BIGINT       DEFAULT 0,
  preview      VARCHAR(500),
  date         DATETIME,
  is_draft     TINYINT      NOT NULL DEFAULT 0,
  deleted      TINYINT      NOT NULL DEFAULT 0,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_msg_folder (folder_id, is_draft, deleted),
  KEY idx_msg_user (user_id),
  KEY idx_msg_uid (account_id, folder_id, remote_uid)
) ENGINE=InnoDB;

-- 附件元数据
CREATE TABLE attachment_meta (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id     BIGINT       NOT NULL,
  message_id  BIGINT,
  account_id  BIGINT,
  name        VARCHAR(512) NOT NULL,
  mime_type   VARCHAR(255),
  size        BIGINT       NOT NULL,
  storage_ref VARCHAR(1024),
  storage_type VARCHAR(16) NOT NULL DEFAULT 'local',  -- local | minio
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_attach_msg (message_id)
) ENGINE=InnoDB;

-- 规则
CREATE TABLE rules (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id    BIGINT       NOT NULL,
  name       VARCHAR(128) NOT NULL,
  priority   INT          NOT NULL DEFAULT 0,
  conditions TEXT,               -- JSON
  actions    TEXT,               -- JSON
  enabled    TINYINT      NOT NULL DEFAULT 1,
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_rule_user (user_id, priority)
) ENGINE=InnoDB;

-- 同步作业
CREATE TABLE sync_jobs (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id     BIGINT NOT NULL,
  account_id  BIGINT NOT NULL,
  folder_id   BIGINT,
  type        VARCHAR(16) NOT NULL,
  status      VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
  last_uid    BIGINT,
  message     VARCHAR(500),
  started_at  DATETIME,
  finished_at DATETIME,
  KEY idx_sync_account (account_id)
) ENGINE=InnoDB;

-- 审计日志
CREATE TABLE audit_logs (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id    BIGINT,
  actor      VARCHAR(64),
  action     VARCHAR(64) NOT NULL,
  target     VARCHAR(255),
  detail     TEXT,
  ip         VARCHAR(64),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_audit_user_time (user_id, created_at)
) ENGINE=InnoDB;
