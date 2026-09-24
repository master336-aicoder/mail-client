-- =====================================================================
-- v5 PST 导出 + 内嵌图片
-- 1) 后台任务扩展：params（JSON 存储任务参数，如导出的文件夹 ID 列表）
--    result（任务结果，如导出文件的相对路径，用于提供下载地址）
-- 2) 附件表增加 content_id：用于将邮件 HTML 中的 cid:xxx 引用映射到附件，
--    以便在客户端展示内嵌图片。
-- =====================================================================

ALTER TABLE tasks
  ADD COLUMN params TEXT NULL,
  ADD COLUMN result VARCHAR(1024) NULL;

ALTER TABLE attachment_meta
  ADD COLUMN content_id VARCHAR(255) NULL,
  ADD COLUMN disposition VARCHAR(16) NULL,
  ADD KEY idx_attach_cid (message_id, content_id);