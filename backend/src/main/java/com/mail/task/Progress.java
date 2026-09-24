package com.mail.task;

import com.mail.task.domain.Task;
import com.mail.task.domain.TaskRepository;

/**
 * 任务进度上报器。线程内无需每次写库，数字变化时写一次。
 */
public class Progress {

    private final Task task;
    private final TaskRepository repo;
    private int lastPersisted = -1;

    public Progress(Task task, TaskRepository repo) {
        this.task = task;
        this.repo = repo;
    }

    /** 更新进度（0-100）与消息。 */
    public synchronized void report(int percent, String message) {
        if (percent < 0) percent = 0;
        if (percent > 100) percent = 100;
        task.setProgress(percent);
        if (message != null) task.setMessage(message);
        if (percent != lastPersisted) {
            lastPersisted = percent;
            repo.save(task);
        }
    }
}
