package com.mail.task;

import com.mail.task.domain.Task;
import com.mail.task.domain.TaskRepository;
import com.mail.common.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/**
 * 后台任务服务：异步执行、进度上报、重试、中止。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskRepository taskRepository;
    private final TaskHandlerRegistry handlerRegistry;
    /** 独立线程池：真正异步执行任务，避免 @Async 自调用失效导致同步阻塞 */
    private final org.springframework.core.task.AsyncTaskExecutor taskExecutor;
    /** 运行中的任务 future，用于中止 */
    private final Map<Long, Future<?>> running = new ConcurrentHashMap<>();

    /**
     * 提交一个新的后台任务。
     */
    @Transactional
    public Task submit(Long userId, String name, String type, Long accountId) {
        return submit(userId, name, type, accountId, null);
    }

    /**
     * 提交一个新的后台任务，params 为 JSON 字符串，供处理器读取任务参数。
     */
    @Transactional
    public Task submit(Long userId, String name, String type, Long accountId, String params) {
        TaskHandler handler = handlerRegistry.get(type);
        if (handler == null) throw new BizException("未知任务类型: " + type);
        Task task = new Task();
        task.setUserId(userId);
        task.setName(name == null ? type : name);
        task.setType(type);
        task.setStatus(Task.PENDING);
        task.setProgress(0);
        task.setAccountId(accountId);
        task.setParams(params);
        taskRepository.save(task);
        execute(task, handler);
        return task;
    }

    /**
     * 重试：以相同参数新建一个任务并异步执行。
     */
    @Transactional
    public Task retry(Long userId, Long taskId) {
        Task old = taskRepository.findById(taskId)
                .orElseThrow(() -> new BizException("任务不存在"));
        if (!old.getUserId().equals(userId)) throw new BizException(40300, "无权操作");
        TaskHandler handler = handlerRegistry.get(old.getType());
        if (handler == null) throw new BizException("任务类型无法重试");
        Task task = new Task();
        task.setUserId(userId);
        task.setName(old.getName());
        task.setType(old.getType());
        task.setStatus(Task.PENDING);
        task.setProgress(0);
        task.setAccountId(old.getAccountId());
        task.setParams(old.getParams());
        taskRepository.save(task);
        execute(task, handler);
        return task;
    }

    /**
     * 中止一个运行中或未开始的任务。
     */
    @Transactional
    public void cancel(Long userId, Long taskId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BizException("任务不存在"));
        if (!task.getUserId().equals(userId)) throw new BizException(40300, "无权操作");
        if (!Task.PENDING.equals(task.getStatus()) && !Task.RUNNING.equals(task.getStatus())) {
            throw new BizException("任务已结束，无法中止");
        }
        Future<?> f = running.remove(taskId);
        if (f != null) f.cancel(true);
        task.setStatus(Task.CANCELLED);
        task.setMessage("任务已中止");
        finish(task);
        taskRepository.save(task);
    }

    @Transactional(readOnly = true)
    public List<Task> list(Long userId) {
        return taskRepository.findByUserIdOrderByIdDesc(userId);
    }

    /**
     * 返回任务状态信息；任务完成后附带下载地址。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> status(Long userId, Long taskId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BizException("任务不存在"));
        if (!task.getUserId().equals(userId)) throw new BizException(40300, "无权操作");
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("taskId", String.valueOf(task.getId()));
        out.put("status", task.getStatus());
        out.put("progress", task.getProgress());
        out.put("message", task.getMessage());
        if (task.getResult() != null && !task.getResult().isBlank()) {
            out.put("downloadUrl", "/api/transfer/" + task.getId() + "/download");
        }
        return out;
    }

    /**
     * 校验任务归属权并返回用于下载的任务。
     */
    @Transactional(readOnly = true)
    public Task getForDownload(Long userId, Long taskId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BizException("任务不存在"));
        if (!task.getUserId().equals(userId)) throw new BizException(40300, "无权操作");
        if (!Task.COMPLETED.equals(task.getStatus())) {
            throw new BizException("任务尚未完成");
        }
        if (task.getResult() == null || task.getResult().isBlank()) {
            throw new BizException("任务没有可下载的产物");
        }
        return task;
    }

    /**
     * 执行任务并跟踪状态。PENDING -> RUNNING -> COMPLETED/FAILED/CANCELLED。
     */
    private void execute(Task task, TaskHandler handler) {
        Long taskId = task.getId();
        // 等事务提交后再放到独立线程池执行，避免背景线程查不到未提交的任务记录
        Runnable start = () -> {
            Future<?> future = taskExecutor.submit(() -> doExecute(taskId));
            running.put(taskId, future);
        };
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            start.run();
                        }
                    });
        } else {
            start.run();
        }
    }

    private void doExecute(Long taskId) {
        Task task = taskRepository.findById(taskId).orElse(null);
        if (task == null) return;
        TaskHandler handler = handlerRegistry.get(task.getType());
        if (handler == null) return;
        task.setStatus(Task.RUNNING);
        task.setStartedAt(LocalDateTime.now());
        task.setError(null);
        taskRepository.save(task);
        Progress progress = new Progress(task, taskRepository);
        try {
            handler.run(task, progress);
            // 若 handler 未显式完成，则标为完成
            if (Task.RUNNING.equals(task.getStatus())) {
                task.setProgress(100);
                task.setStatus(Task.COMPLETED);
                task.setMessage("完成");
                finish(task);
                taskRepository.save(task);
            }
        } catch (Exception e) {
            log.warn("任务 {} 执行失败: {}", task.getId(), e.getMessage());
            task.setStatus(Task.FAILED);
            task.setError(e.getMessage() == null ? e.toString() : e.getMessage());
            task.setMessage("失败");
            finish(task);
            taskRepository.save(task);
        } finally {
            running.remove(taskId);
        }
    }

    private void finish(Task task) {
        task.setFinishedAt(LocalDateTime.now());
        if (task.getStartedAt() != null) {
            task.setDurationMs(Duration.between(task.getStartedAt(), task.getFinishedAt()).toMillis());
        }
    }
}