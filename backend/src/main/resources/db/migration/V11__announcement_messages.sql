-- =====================================================================
-- v11 站内公告（站内信发布能力）
--
-- 管理员发布一条公告 = 按收件人扇出成多行 notifications（type=ANNOUNCEMENT），
-- 同一次发布共用 batch_no，用于发布历史与阅读进度统计；sender_id 记录发布人。
-- 事件通知（新邮件、收发失败等）这两列为 NULL，行为完全不变。
-- =====================================================================
ALTER TABLE notifications
  ADD COLUMN sender_id BIGINT      NULL AFTER link,
  ADD COLUMN batch_no  VARCHAR(64) NULL AFTER sender_id,
  ADD INDEX idx_notif_batch (batch_no);
