-- =====================================================================
-- v3 规则以账户为基准：rules 增加 account_id
-- =====================================================================

ALTER TABLE rules ADD COLUMN account_id BIGINT NULL AFTER user_id;

CREATE INDEX idx_rule_user_account ON rules (user_id, account_id, priority);