-- =====================================================================
-- v12 收信频率：间隔单位与每日定点两种模式
--
-- 账户级排程原先只有 sync_interval_sec（纯秒数间隔）。这里加两列：
--   sync_mode       INTERVAL=按间隔轮询（默认）/ DAILY=每天定点收一次
--   sync_daily_time DAILY 模式下的执行时刻（HH:mm）
-- tasks 侧同步镜像这两列：排程统一在 TaskService.nextRunAt 一个地方算，
-- 避免调度器再去回查账户配置。
-- =====================================================================
ALTER TABLE mail_accounts
  ADD COLUMN sync_mode       VARCHAR(16) NOT NULL DEFAULT 'INTERVAL' AFTER sync_interval_sec,
  ADD COLUMN sync_daily_time TIME        NULL                        AFTER sync_mode;

ALTER TABLE tasks
  ADD COLUMN schedule_mode VARCHAR(16) NOT NULL DEFAULT 'INTERVAL' AFTER schedule_sec,
  ADD COLUMN daily_time    TIME        NULL                        AFTER schedule_mode;
