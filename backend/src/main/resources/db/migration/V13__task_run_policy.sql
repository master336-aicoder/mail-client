-- =====================================================================
-- v13 任务级执行记录保存策略
--
-- v8 的策略只有用户级（全局）一档，但「导出类一次性任务」和「账户同步周期任务」
-- 需要的保留期完全不同：前者产物只想取一次，后者要留够排查窗口。
-- 给任务定义加两列可空覆盖，为空即跟随全局：
--   run_retention_scope  ALL / SUCCESS / FAILED / NONE
--   run_retention_days   保存天数
-- =====================================================================

ALTER TABLE tasks
  ADD COLUMN run_retention_scope VARCHAR(16) NULL,
  ADD COLUMN run_retention_days  INT         NULL;
