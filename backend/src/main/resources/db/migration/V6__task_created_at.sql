-- =====================================================================
-- v6 后台任务创建时间
-- 任务列表默认按「创建时间倒序」分页，但 tasks 只有 started_at（未开始/被中止的
-- 任务为空），无法表达创建时间，因此补 created_at 并回填历史数据。
-- 回填优先取已知的开始/结束时间；两者都为空的行按 id 从最新已知时间往前逐秒排开，
-- 保证历史行之间的相对顺序仍是「后创建的在前」。
-- =====================================================================

ALTER TABLE tasks ADD COLUMN created_at DATETIME NULL;

SET @hi := COALESCE((SELECT MAX(COALESCE(started_at, finished_at)) FROM tasks), NOW());
SET @max_id := COALESCE((SELECT MAX(id) FROM tasks), 0);

UPDATE tasks SET created_at = COALESCE(started_at, finished_at,
    DATE_SUB(@hi, INTERVAL (@max_id - id) SECOND));

ALTER TABLE tasks MODIFY COLUMN created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE INDEX idx_tasks_user_created ON tasks (user_id, created_at, id);
