package com.mail.task;

import com.mail.task.domain.Task;

/**
 * 后台任务处理器。
 */
public interface TaskHandler {

    /** 任务类型，如 account_sync */
    String type();

    /** 名称前缀 */
    String name();

    /** 执行任务，通过 progress 上报进度；返回时任务将被标记为完成。 */
    void run(Task task, Progress progress) throws Exception;

    default boolean canRetry() {
        return true;
    }
}
