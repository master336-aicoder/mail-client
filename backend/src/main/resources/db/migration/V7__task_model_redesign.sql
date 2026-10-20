-- =====================================================================
-- v7 后台任务模型重构
--
-- 旧模型把「每次执行」当成一条任务，定时收信因此每 15 秒堆积一行，任务列表
-- 无法回答「这个账户的同步任务怎么样了」。新模型：
--   tasks        任务定义：账户同步 = 每账户一条周期性任务；导入导出 = 每次手动操作一条一次性任务
--   task_runs    执行记录：一次执行一行，含状态/进度/说明/耗时/结果
--   task_attachments  执行产物：任务跑完产生的文件（如导出包），供列表下载
-- =====================================================================

-- 1) 任务定义新增调度字段
ALTER TABLE tasks
  ADD COLUMN periodic     TINYINT(1) NOT NULL DEFAULT 0,
  ADD COLUMN enabled      TINYINT(1) NOT NULL DEFAULT 1,
  ADD COLUMN schedule_sec INT        NULL,
  ADD COLUMN last_run_at  DATETIME   NULL,
  ADD COLUMN next_run_at  DATETIME   NULL;

-- 2) 执行记录与产物
CREATE TABLE task_runs (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id      BIGINT       NOT NULL,
  status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
  progress     INT          NOT NULL DEFAULT 0,
  message      VARCHAR(500),
  error        VARCHAR(1000),
  result       VARCHAR(1024),
  trigger_type VARCHAR(16)  NOT NULL DEFAULT 'MANUAL',
  started_at   DATETIME,
  finished_at  DATETIME,
  duration_ms  BIGINT,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_run_task FOREIGN KEY (task_id) REFERENCES tasks (id) ON DELETE CASCADE
) ENGINE = InnoDB;

CREATE INDEX idx_run_task_id ON task_runs (task_id, id);

CREATE TABLE task_attachments (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  run_id       BIGINT       NOT NULL,
  task_id      BIGINT       NOT NULL,
  user_id      BIGINT       NOT NULL,
  name         VARCHAR(255) NOT NULL,
  path         VARCHAR(1024) NOT NULL,
  size_bytes   BIGINT,
  content_type VARCHAR(128),
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_att_run FOREIGN KEY (run_id) REFERENCES task_runs (id) ON DELETE CASCADE
) ENGINE = InnoDB;

CREATE INDEX idx_att_run_id ON task_attachments (run_id);
CREATE INDEX idx_att_user_id ON task_attachments (user_id);

-- 3) 历史数据：旧任务行 → 执行记录（先按原 task_id 落库，下一步再归并）
INSERT INTO task_runs (task_id, status, progress, message, error, result, trigger_type,
                       started_at, finished_at, duration_ms, created_at)
SELECT id, status, progress, message, error, result,
       CASE WHEN name LIKE '定时收取%' THEN 'SCHEDULED' ELSE 'MANUAL' END,
       started_at, finished_at, duration_ms, COALESCE(created_at, started_at, finished_at, NOW())
FROM tasks;

-- 4) 导出产物 → 附件列表
INSERT INTO task_attachments (run_id, task_id, user_id, name, path, content_type)
SELECT r.id, r.task_id, t.user_id, SUBSTRING_INDEX(t.result, '/', -1), t.result, 'application/zip'
FROM task_runs r
  JOIN tasks t ON t.id = r.task_id
WHERE t.type = 'pst_export' AND t.result IS NOT NULL AND t.result <> '';

-- 5) 账户同步归并：每个（用户, 账户）只保留 id 最大的一行作为周期任务
CREATE TEMPORARY TABLE tmp_sync_keep AS
SELECT user_id, COALESCE(account_id, -1) AS grp, MAX(id) AS keep_id
FROM tasks WHERE type = 'account_sync'
GROUP BY user_id, COALESCE(account_id, -1);

UPDATE task_runs r
  JOIN tasks t ON t.id = r.task_id
  JOIN tmp_sync_keep k ON k.user_id = t.user_id AND k.grp = COALESCE(t.account_id, -1)
SET r.task_id = k.keep_id
WHERE t.type = 'account_sync' AND t.id <> k.keep_id;

DELETE t FROM tasks t
  LEFT JOIN tmp_sync_keep k ON k.user_id = t.user_id AND k.grp = COALESCE(t.account_id, -1)
WHERE t.type = 'account_sync' AND t.id <> k.keep_id;

DROP TEMPORARY TABLE tmp_sync_keep;

-- 5b) 账户已被删除的同步任务失去意义，连执行记录一起清掉
DELETE t FROM tasks t
  LEFT JOIN mail_accounts a ON a.id = t.account_id
WHERE t.type = 'account_sync' AND a.id IS NULL;

-- 6) 归并后的同步任务标记为周期任务，启用状态跟随账户
UPDATE tasks t
  JOIN mail_accounts a ON a.id = t.account_id
SET t.periodic = 1,
    t.enabled = a.is_active,
    t.schedule_sec = a.sync_interval_sec,
    t.name = CONCAT('账户同步 ', a.email)
WHERE t.type = 'account_sync';

UPDATE tasks SET periodic = 1, enabled = 0 WHERE type = 'account_sync' AND periodic = 0;

-- 7) 执行记录回填到任务定义的「上次执行」，到期的周期任务下次立即执行
UPDATE tasks t
SET t.last_run_at = (SELECT MAX(COALESCE(r.finished_at, r.started_at)) FROM task_runs r WHERE r.task_id = t.id);

UPDATE tasks SET next_run_at = NOW() WHERE periodic = 1 AND enabled = 1;

-- 8) 一次性任务补上可读名称
UPDATE tasks SET name = '邮件导出' WHERE type = 'pst_export' AND (name IS NULL OR name = '');

-- 9) 每次执行的状态列从任务定义上移除，改由 task_runs 承载
ALTER TABLE tasks
  DROP COLUMN status,
  DROP COLUMN progress,
  DROP COLUMN started_at,
  DROP COLUMN finished_at,
  DROP COLUMN duration_ms,
  DROP COLUMN message,
  DROP COLUMN error,
  DROP COLUMN result;

CREATE INDEX idx_tasks_periodic ON tasks (periodic, enabled, next_run_at);
