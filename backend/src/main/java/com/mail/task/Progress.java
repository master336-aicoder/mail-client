package com.mail.task;

import com.mail.task.domain.TaskAttachment;
import com.mail.task.domain.TaskRun;
import com.mail.task.domain.TaskRunRepository;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * 单次执行的进度上报器。线程内无需每次写库，数字变化时写一次。
 *
 * <p>执行产物通过 {@link #attach} 登记，只有执行成功后才会落库成可下载的附件，
 * 失败或中止的半成品不会出现在附件列表里。
 */
public class Progress {

    private final TaskRun run;
    private final TaskRunRepository repo;
    private final Long userId;
    private int lastPersisted = -1;

    /** 本次执行的 id：产物登记需要回填。 */
    public Long runId() {
        return run.getId();
    }

    @Getter
    private final List<TaskAttachment> attachments = new ArrayList<>();

    Progress(TaskRun run, TaskRunRepository repo, Long userId) {
        this.run = run;
        this.repo = repo;
        this.userId = userId;
    }

    /** 更新进度（0-100）与说明。 */
    public synchronized void report(int percent, String message) {
        if (percent < 0) percent = 0;
        if (percent > 100) percent = 100;
        run.setProgress(percent);
        if (message != null) run.setMessage(message);
        if (percent != lastPersisted) {
            lastPersisted = percent;
            // 定向更新而非实体 save：执行被中止后不会再覆盖回进行中状态
            repo.markProgress(run.getId(), percent, run.getMessage());
        }
    }

    /** 执行结果摘要，展示在「结果」列。 */
    public synchronized void result(String summary) {
        run.setResult(summary);
    }

    /**
     * 登记一个产物文件。
     *
     * @param relativePath 相对导出根目录的路径，下载时拼接并校验不越界
     */
    public synchronized void attach(String name, String relativePath, long sizeBytes, String contentType) {
        TaskAttachment a = new TaskAttachment();
        a.setRunId(run.getId());
        a.setTaskId(run.getTaskId());
        a.setUserId(userId);
        a.setName(name);
        a.setPath(relativePath);
        a.setSizeBytes(sizeBytes);
        a.setContentType(contentType);
        attachments.add(a);
    }
}
