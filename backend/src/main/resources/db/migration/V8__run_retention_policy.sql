-- =====================================================================
-- v8 执行记录保存策略
--
-- 周期同步任务每轮都会产生一条执行记录，长期运行会不断堆积。
-- 在用户级设置中增加保存策略：
--   run_retention_scope  ALL 全部保留 / SUCCESS 只留成功 / FAILED 只留失败 / NONE 不保留
--   run_retention_days   保存天数（超过即清理），默认 7 天
-- =====================================================================

ALTER TABLE user_settings
  ADD COLUMN run_retention_scope VARCHAR(16) NOT NULL DEFAULT 'ALL',
  ADD COLUMN run_retention_days  INT         NOT NULL DEFAULT 7;
