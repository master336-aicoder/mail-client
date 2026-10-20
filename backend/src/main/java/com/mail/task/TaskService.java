package com.mail.task;

import com.mail.account.SettingsService;
import com.mail.account.domain.MailAccount;
import com.mail.account.domain.MailAccountRepository;
import com.mail.account.domain.UserSetting;
import com.mail.account.domain.UserSettingRepository;
import com.mail.common.BizException;
import com.mail.common.MailErrors;
import com.mail.common.PageResult;
import com.mail.notification.NotificationService;
import com.mail.notification.domain.Notification;
import com.mail.task.domain.Task;
import com.mail.task.domain.TaskAttachment;
import com.mail.task.domain.TaskAttachmentRepository;
import com.mail.task.domain.TaskRepository;
import com.mail.task.domain.TaskRun;
import com.mail.task.domain.TaskRunRepository;
import com.mail.task.dto.AttachmentItem;
import com.mail.task.dto.TaskListItem;
import com.mail.task.dto.TaskRunItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/**
 * 后台任务服务。
 *
 * <p>模型：{@link Task} 是任务定义（账户同步 = 每账户一条周期任务，导入导出 = 每次操作一条一次性任务），
 * {@link TaskRun} 是每次的执行记录，{@link TaskAttachment} 是执行产生的可下载产物。
 * 周期任务由 {@link PeriodicTaskRunner} 按 {@code nextRunAt} 驱动，因此同步一个账户永远不会堆积出上千条任务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    /** 每页上限：拒绝前端一次拉走全表。 */
    private static final int MAX_PAGE_SIZE = 200;
    /** 周期任务最小间隔，避免配成 0 秒空转。 */
    public static final int MIN_INTERVAL_SEC = 15;
    /** 定点排程缺省时刻：凌晨低峰。 */
    private static final LocalTime DEFAULT_DAILY_TIME = LocalTime.of(3, 0);
    private static final List<String> LIVE = List.of(TaskRun.PENDING, TaskRun.RUNNING);
    private static final List<String> FINISHED = List.of(TaskRun.COMPLETED, TaskRun.FAILED, TaskRun.CANCELLED);
    /** 认证被拒后的退避区间：太短会继续触发服务商限流，太长会让改好配置的用户等不到收信。 */
    private static final Duration AUTH_BACKOFF_FLOOR = Duration.ofMinutes(10);
    private static final Duration AUTH_BACKOFF_CAP = Duration.ofHours(1);
    /** 守护进程回收孤儿执行的宽限期：早于建记录这么久、且进程内已无存活执行线程才判为孤儿，
     *  避免误伤「事务提交后到线程池真正 submit 之前」的正常排队窗口。 */
    public static final Duration ORPHAN_GRACE = Duration.ofMinutes(2);

    private final TaskRepository taskRepository;
    private final TaskRunRepository runRepository;
    private final TaskAttachmentRepository attachmentRepository;
    private final TaskHandlerRegistry handlerRegistry;
    private final MailAccountRepository accountRepository;
    private final SettingsService settingsService;
    private final UserSettingRepository settingRepository;
    private final AttachmentFiles attachmentFiles;
    private final NotificationService notificationService;
    /** 独立线程池：真正异步执行任务，避免 @Async 自调用失效导致同步阻塞 */
    private final AsyncTaskExecutor taskExecutor;
    /** 运行中的执行 future，用于中止 */
    private final Map<Long, Future<?>> running = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ 任务定义

    /**
     * 保证账户有一条配置一致的周期同步任务：账户创建、改频率、停用/启用后都要调用，
     * 任务的启用状态与排程以账户为准。
     * 排程真正变化时重算下次执行时间，否则改一次「每天 08:00」还要等旧间隔走完。
     */
    @Transactional
    public Task ensureAccountSyncTask(MailAccount acc) {
        Optional<Task> existing = taskRepository
                .findByUserIdAndTypeAndAccountId(acc.getUserId(), AccountSyncTaskHandler.TYPE, acc.getId());
        String mode = dailySync(acc) ? Task.SCHEDULE_DAILY : Task.SCHEDULE_INTERVAL;
        LocalTime dailyTime = dailySync(acc) ? acc.getSyncDailyTime() : null;
        boolean reschedule = existing.map(prev -> !Objects.equals(prev.getScheduleMode(), mode)
                || !Objects.equals(prev.getScheduleSec(), acc.getSyncIntervalSec())
                || !Objects.equals(prev.getDailyTime(), dailyTime)).orElse(false);
        Task t = existing.orElseGet(() -> {
            Task n = new Task();
            n.setUserId(acc.getUserId());
            n.setType(AccountSyncTaskHandler.TYPE);
            n.setAccountId(acc.getId());
            n.setPeriodic(true);
            n.setNextRunAt(LocalDateTime.now());
            return n;
        });
        t.setName("账户同步 " + acc.getEmail());
        t.setScheduleSec(acc.getSyncIntervalSec());
        t.setScheduleMode(mode);
        t.setDailyTime(dailyTime);
        t.setEnabled(acc.isActive());
        if (t.isEnabled() && (t.getNextRunAt() == null || reschedule)) t.setNextRunAt(nextRunAtOnChange(t));
        if (!t.isEnabled()) t.setNextRunAt(null);
        return taskRepository.save(t);
    }

    /** 账户是否为「每天定点」排程：时刻缺失时退回按间隔，不让配置残缺把任务卡死。 */
    private static boolean dailySync(MailAccount acc) {
        return MailAccount.SYNC_DAILY.equalsIgnoreCase(acc.getSyncMode()) && acc.getSyncDailyTime() != null;
    }

    /** 删除账户的同步任务；执行记录与附件记录由外键级联清理。 */
    @Transactional
    public void removeAccountTasks(Long accountId) {
        taskRepository.deleteAll(taskRepository.findByAccountId(accountId));
    }

    /**
     * 该账户的周期同步任务当前是否有排队 / 进行中的执行。
     * 重置前用于拦截：本轮尚未跑完就清库会与正在写库的线程抢同一批数据。
     */
    @Transactional(readOnly = true)
    public boolean syncRunActive(MailAccount acc) {
        return taskRepository
                .findByUserIdAndTypeAndAccountId(acc.getUserId(), AccountSyncTaskHandler.TYPE, acc.getId())
                .map(t -> runRepository.existsByTaskIdAndStatusIn(t.getId(), LIVE))
                .orElse(false);
    }

    /** 新建一次性任务并立即执行（如一次导出）。 */
    @Transactional
    public Task submitOneOff(Long userId, String name, String type, Long accountId, String params) {
        handlerRegistry.get(type);
        Task t = new Task();
        t.setUserId(userId);
        t.setName(name);
        t.setType(type);
        t.setAccountId(accountId);
        t.setParams(params);
        t.setPeriodic(false);
        t.setEnabled(true);
        taskRepository.save(t);
        runInternal(t, TaskRun.TRIGGER_MANUAL);
        return t;
    }

    // ------------------------------------------------------------------ 执行

    /** 手动立即执行。 */
    @Transactional
    public TaskRun runNow(Long userId, Long taskId) {
        return runInternal(own(userId, taskId), TaskRun.TRIGGER_MANUAL);
    }

    /** 周期调度触发（调度器调用，不做归属校验）。 */
    @Transactional
    public TaskRun runScheduled(Long taskId) {
        Task t = taskRepository.findById(taskId).orElseThrow(() -> new BizException("任务不存在"));
        return runInternal(t, TaskRun.TRIGGER_SCHEDULED);
    }

    private TaskRun runInternal(Task task, String trigger) {
        handlerRegistry.get(task.getType());
        if (runRepository.existsByTaskIdAndStatusIn(task.getId(), LIVE)) {
            throw new BizException("该任务正在执行中，请等待本轮结束后再试");
        }
        TaskRun run = new TaskRun();
        run.setTaskId(task.getId());
        run.setTriggerType(trigger);
        run.setStatus(TaskRun.PENDING);
        run.setProgress(0);
        run.setMessage("排队中");
        runRepository.save(run);
        task.setLastRunAt(LocalDateTime.now());
        task.setNextRunAt(nextRunAt(task));
        taskRepository.save(task);
        start(run.getId());
        return run;
    }

    /** 下次执行时间：仅启用的周期任务有值。 */
    private LocalDateTime nextRunAt(Task task) {
        if (!task.isPeriodic() || !task.isEnabled()) return null;
        if (Task.SCHEDULE_DAILY.equals(task.getScheduleMode())) {
            return nextDailySlot(task.getDailyTime());
        }
        return LocalDateTime.now().plusSeconds(effectiveIntervalSec(task));
    }

    /**
     * 每日定点的下一次时刻：今天的槽位已过（或正好触发这一轮）就顺延到明天。
     * 用「严格晚于当前」判断，避免调度器在同一分钟内反复触发。
     */
    private LocalDateTime nextDailySlot(LocalTime at) {
        LocalTime time = at == null ? DEFAULT_DAILY_TIME : at;
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime slot = now.toLocalDate().atTime(time.withSecond(0).withNano(0));
        return slot.isAfter(now) ? slot : slot.plusDays(1);
    }

    /** 改配置或重新启用后的首跳：定点任务等下一个槽位，间隔任务立即跑一轮让新配置生效。 */
    private LocalDateTime nextRunAtOnChange(Task task) {
        if (!task.isPeriodic() || !task.isEnabled()) return null;
        return Task.SCHEDULE_DAILY.equals(task.getScheduleMode())
                ? nextDailySlot(task.getDailyTime()) : LocalDateTime.now();
    }

    /** 生效周期：任务自定义优先，否则跟随用户级默认收取频率。 */
    public int effectiveIntervalSec(Task task) {
        int sec = task.getScheduleSec() != null
                ? task.getScheduleSec() : settingsService.defaultSyncIntervalSec(task.getUserId());
        return Math.max(MIN_INTERVAL_SEC, sec);
    }

    /** 等事务提交后再丢进线程池，避免工作线程查不到未提交的记录。 */
    private void start(Long runId) {
        Runnable begin = () -> running.put(runId, taskExecutor.submit(() -> doExecute(runId)));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            begin.run();
                        }
                    });
        } else {
            begin.run();
        }
    }

    private void doExecute(Long runId) {
        TaskRun run = runRepository.findById(runId).orElse(null);
        if (run == null) return;
        Task task = taskRepository.findById(run.getTaskId()).orElse(null);
        if (task == null) return;
        TaskHandler handler;
        try {
            handler = handlerRegistry.get(task.getType());
        } catch (Exception e) {
            fail(run, task, e);
            return;
        }
        run.setStatus(TaskRun.RUNNING);
        run.setStartedAt(LocalDateTime.now());
        run.setError(null);
        runRepository.save(run);
        Progress progress = new Progress(run, runRepository, task.getUserId());
        try {
            handler.run(task, progress);
            // 已被中止的执行不再回写终态；已上报的进度说明保留不覆盖
            if (stillRunning(runId)) {
                run.setProgress(100);
                run.setStatus(TaskRun.COMPLETED);
                if (run.getMessage() == null || run.getMessage().isBlank()) {
                    run.setMessage("执行完成");
                }
                finish(run);
                runRepository.save(run);
                if (!progress.getAttachments().isEmpty()) {
                    attachmentRepository.saveAll(progress.getAttachments());
                }
            }
        } catch (Exception e) {
            // 中止走的是 interrupt，线程会以异常退出；此时库里已是 CANCELLED，别改写成失败
            if (stillRunning(runId)) {
                log.warn("任务 {} 执行失败: {}", runId, e.getMessage(), e);
                fail(run, task, e);
            }
        } finally {
            running.remove(runId);
        }
    }

    private void fail(TaskRun run, Task task, Exception e) {
        String reason = MailErrors.explain(e);
        run.setStatus(TaskRun.FAILED);
        run.setError(reason);
        run.setMessage("失败：" + reason);
        finish(run);
        runRepository.save(run);
        deferAfterAuthFailure(task, e);
        notifyFailure(task, reason);
    }

    /**
     * 认证被服务器拒绝后推迟下一次周期执行：服务商对连续失败有计数，且收信/发信共用同一计数，
     * 按原频率（最短 15 秒）重试只会不断延长锁定，把本来正常的发信一起拖垮。
     * 服务器给出冷却时长时按其加一分钟，否则退避到下限。
     */
    private void deferAfterAuthFailure(Task task, Exception e) {
        if (task == null || !task.isPeriodic() || !task.isEnabled()
                || !Task.SCHEDULE_INTERVAL.equals(task.getScheduleMode())) return;
        if (!MailErrors.isAuthRejected(e)) return;
        Integer retryIn = MailErrors.retryAfterSeconds(e);
        Duration wait = AUTH_BACKOFF_FLOOR;
        if (retryIn != null) {
            Duration serverAsked = Duration.ofSeconds(retryIn + 60L);
            if (serverAsked.compareTo(wait) > 0) wait = serverAsked;
        }
        if (wait.compareTo(AUTH_BACKOFF_CAP) > 0) wait = AUTH_BACKOFF_CAP;
        task.setNextRunAt(LocalDateTime.now().plus(wait));
        taskRepository.save(task);
        log.info("任务 {} 认证被拒，下一次执行退避至 {}", task.getId(), task.getNextRunAt());
    }

    /**
     * 收信失败要主动提醒：周期任务在后台跑，用户不会盯着任务列表，
     * 密码失效、服务器故障一类问题一旦不通知就是静默丢邮件。
     * 标题不含具体原因，配合冷却窗口避免同一故障每轮重复通知。
     */
    private void notifyFailure(Task task, String reason) {
        if (task == null || !AccountSyncTaskHandler.TYPE.equals(task.getType())) return;
        notificationService.notifyThrottled(task.getUserId(),
                Notification.RECEIVE_FAILED,
                "收信失败：" + task.getName(),
                "原因：" + reason + "。可在「后台任务」中重试，或到「账户设置」检查服务器与密码。",
                "/tasks", Duration.ofMinutes(30));
    }

    /** 库里是否仍处于进行中：被中止（或已被重启回收）的执行不再回写终态。 */
    private boolean stillRunning(Long runId) {
        return TaskRun.RUNNING.equals(runRepository.findStatus(runId));
    }

    private void finish(TaskRun run) {
        run.setFinishedAt(LocalDateTime.now());
        if (run.getStartedAt() != null) {
            run.setDurationMs(Duration.between(run.getStartedAt(), run.getFinishedAt()).toMillis());
        }
    }

    // ------------------------------------------------------------------ 中止 / 批量

    @Transactional
    public void cancel(Long userId, Long taskId) {
        own(userId, taskId);
        TaskRun live = liveRun(taskId).orElseThrow(() -> new BizException("该任务当前没有排队或进行中的执行"));
        doCancel(live);
    }

    private Optional<TaskRun> liveRun(Long taskId) {
        return runRepository.findByTaskIdInOrderByIdDesc(List.of(taskId)).stream()
                .filter(r -> LIVE.contains(r.getStatus()))
                .findFirst();
    }

    private void doCancel(TaskRun run) {
        Future<?> f = running.remove(run.getId());
        if (f != null) f.cancel(true);
        run.setStatus(TaskRun.CANCELLED);
        run.setMessage("已中止");
        finish(run);
        runRepository.save(run);
    }

    /** 批量立即执行：跳过正在执行的任务，返回实际启动数。 */
    @Transactional
    public int batchRun(Long userId, List<Long> ids) {
        int started = 0;
        for (Task t : owned(userId, ids)) {
            if (runRepository.existsByTaskIdAndStatusIn(t.getId(), LIVE)) continue;
            if (handlerRegistry.find(t.getType()) == null) continue;
            runInternal(t, TaskRun.TRIGGER_MANUAL);
            started++;
        }
        return started;
    }

    /** 批量中止：只作用于正在排队/执行的记录。 */
    @Transactional
    public int batchCancel(Long userId, List<Long> ids) {
        int cancelled = 0;
        for (Task t : owned(userId, ids)) {
            Optional<TaskRun> live = liveRun(t.getId());
            if (live.isPresent()) {
                doCancel(live.get());
                cancelled++;
            }
        }
        return cancelled;
    }

    /** 启用/停用单个周期任务。 */
    @Transactional
    public void setEnabled(Long userId, Long taskId, boolean enabled) {
        Task t = own(userId, taskId);
        if (!t.isPeriodic()) throw new BizException("一次性任务没有启停状态");
        t.setEnabled(enabled);
        t.setNextRunAt(nextRunAtOnChange(t));
        taskRepository.save(t);
    }

    /** 批量启用/停用周期任务；停用时清空下次执行时间。 */
    @Transactional
    public int batchSetEnabled(Long userId, List<Long> ids, boolean enabled) {
        int updated = 0;
        for (Task t : owned(userId, ids)) {
            if (!t.isPeriodic()) continue;
            t.setEnabled(enabled);
            t.setNextRunAt(nextRunAtOnChange(t));
            taskRepository.save(t);
            updated++;
        }
        return updated;
    }

    /**
     * 批量删除任务定义。执行记录与附件随外键级联删除；
     * 正在执行的先中止，磁盘上的产物文件保留（由导出目录自行清理）。
     */
    @Transactional
    public int batchDelete(Long userId, List<Long> ids) {
        List<Task> list = owned(userId, ids);
        for (Task t : list) {
            liveRun(t.getId()).ifPresent(this::doCancel);
        }
        taskRepository.deleteAll(list);
        return list.size();
    }

    // ------------------------------------------------------------------ 执行记录批量操作

    /**
     * 批量删除执行记录：传 ids 删选中的，传 taskId 删该任务的全部；
     * 进行中/排队的记录不受影响，产物附件记录随执行记录级联删除。
     */
    @Transactional
    public int batchDeleteRuns(Long userId, List<Long> ids, Long taskId) {
        List<Long> targets = new ArrayList<>();
        if (taskId != null) {
            own(userId, taskId);
            targets.addAll(runRepository.findFinishedIdsByTask(taskId));
        } else {
            if (ids == null || ids.isEmpty()) throw new BizException("请选择要操作的执行记录");
            for (TaskRun r : runRepository.findAllById(ids)) {
                Task t = taskRepository.findById(r.getTaskId()).orElse(null);
                if (t == null || !t.getUserId().equals(userId)) continue;
                if (FINISHED.contains(r.getStatus())) targets.add(r.getId());
            }
        }
        if (!targets.isEmpty()) runRepository.deleteAllById(targets);
        return targets.size();
    }

    /** 发起一次「执行记录导出」任务：同样支持选中或按任务全部导出。 */
    @Transactional
    public Task submitRunsExport(Long userId, List<Long> ids, Long taskId) {
        List<Long> targets = new ArrayList<>();
        if (taskId != null) {
            own(userId, taskId);
            targets.addAll(runRepository.findFinishedIdsByTask(taskId));
        } else {
            if (ids == null || ids.isEmpty()) throw new BizException("请选择要导出的执行记录");
            for (TaskRun r : runRepository.findAllById(ids)) {
                Task t = taskRepository.findById(r.getTaskId()).orElse(null);
                if (t == null || !t.getUserId().equals(userId)) continue;
                targets.add(r.getId());
            }
        }
        if (targets.isEmpty()) throw new BizException("没有可导出的执行记录");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("runIds", targets);
        String json;
        try {
            json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(params);
        } catch (Exception e) {
            throw new BizException("导出参数序列化失败");
        }
        return submitOneOff(userId, "记录导出", TaskRunExportTaskHandler.TYPE, null, json);
    }

    private List<Task> owned(Long userId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return taskRepository.findAllById(ids).stream()
                .filter(t -> t.getUserId().equals(userId))
                .toList();
    }

    private Task own(Long userId, Long taskId) {
        Task t = taskRepository.findById(taskId).orElseThrow(() -> new BizException("任务不存在"));
        if (!t.getUserId().equals(userId)) throw new BizException(40300, "无权操作");
        return t;
    }

    // ------------------------------------------------------------------ 查询

    /** 分页任务列表：默认创建时间倒序，每项带最近一次执行的状态。 */
    @Transactional(readOnly = true)
    public PageResult<TaskListItem> page(Long userId, int page, int size, String type) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(safePage - 1, safeSize,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<Task> result = (type == null || type.isBlank())
                ? taskRepository.findByUserId(userId, pageable)
                : taskRepository.findByUserIdAndType(userId, type, pageable);
        return new PageResult<>(result.getTotalElements(), safePage, safeSize, decorate(result.getContent()));
    }

    /** 为任务列表补充最近一次执行、账户邮箱与附件数。 */
    private List<TaskListItem> decorate(List<Task> tasks) {
        if (tasks.isEmpty()) return List.of();
        List<Long> taskIds = tasks.stream().map(Task::getId).toList();
        Map<Long, TaskRun> latest = new LinkedHashMap<>();
        for (TaskRun r : runRepository.findByTaskIdInOrderByIdDesc(taskIds)) {
            latest.putIfAbsent(r.getTaskId(), r);
        }
        List<Long> runIds = latest.values().stream().map(TaskRun::getId).toList();
        Map<Long, Integer> attCount = new LinkedHashMap<>();
        for (TaskAttachment a : attachmentRepository.findByRunIdInOrderByIdAsc(runIds)) {
            attCount.merge(a.getRunId(), 1, Integer::sum);
        }
        Map<Long, String> emails = new LinkedHashMap<>();
        for (MailAccount a : accountRepository.findAllById(
                tasks.stream().map(Task::getAccountId).filter(java.util.Objects::nonNull).distinct().toList())) {
            emails.put(a.getId(), a.getEmail());
        }
        List<TaskListItem> out = new ArrayList<>();
        for (Task t : tasks) {
            TaskListItem i = new TaskListItem();
            i.setId(t.getId());
            i.setName(t.getName());
            i.setType(t.getType());
            i.setAccountId(t.getAccountId());
            i.setAccountEmail(t.getAccountId() == null ? null : emails.get(t.getAccountId()));
            i.setPeriodic(t.isPeriodic());
            i.setEnabled(t.isEnabled());
            i.setScheduleSec(t.getScheduleSec());
            i.setScheduleMode(t.getScheduleMode());
            i.setDailyTime(t.getDailyTime());
            i.setRunRetentionScope(t.getRunRetentionScope());
            i.setRunRetentionDays(t.getRunRetentionDays());
            boolean daily = Task.SCHEDULE_DAILY.equals(t.getScheduleMode());
            i.setEffectiveScheduleSec(t.isPeriodic() && !daily ? effectiveIntervalSec(t) : null);
            i.setCreatedAt(t.getCreatedAt());
            i.setLastRunAt(t.getLastRunAt());
            i.setNextRunAt(t.getNextRunAt());
            TaskRun r = latest.get(t.getId());
            if (r != null) {
                i.setLastRunId(r.getId());
                i.setStatus(r.getStatus());
                i.setProgress(r.getProgress());
                i.setMessage(r.getMessage());
                i.setError(r.getError());
                i.setResult(r.getResult());
                i.setTriggerType(r.getTriggerType());
                i.setStartedAt(r.getStartedAt());
                i.setFinishedAt(r.getFinishedAt());
                i.setDurationMs(r.getDurationMs());
                i.setAttachmentCount(attCount.getOrDefault(r.getId(), 0));
            }
            out.add(i);
        }
        return out;
    }

    /** 某任务的执行记录分页（新的在前），含产物附件。 */
    @Transactional(readOnly = true)
    public PageResult<TaskRunItem> runs(Long userId, Long taskId, int page, int size) {
        own(userId, taskId);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Page<TaskRun> result = runRepository.findByTaskId(taskId,
                PageRequest.of(safePage - 1, safeSize, Sort.by(Sort.Order.desc("id"))));
        List<TaskRun> list = result.getContent();
        Map<Long, List<AttachmentItem>> byRun = new LinkedHashMap<>();
        for (TaskAttachment a : attachmentRepository.findByRunIdInOrderByIdAsc(
                list.stream().map(TaskRun::getId).toList())) {
            byRun.computeIfAbsent(a.getRunId(), k -> new ArrayList<>()).add(toItem(a));
        }
        List<TaskRunItem> items = new ArrayList<>();
        for (TaskRun r : list) {
            TaskRunItem i = new TaskRunItem();
            i.setId(r.getId());
            i.setTaskId(r.getTaskId());
            i.setStatus(r.getStatus());
            i.setProgress(r.getProgress());
            i.setMessage(r.getMessage());
            i.setError(r.getError());
            i.setResult(r.getResult());
            i.setTriggerType(r.getTriggerType());
            i.setStartedAt(r.getStartedAt());
            i.setFinishedAt(r.getFinishedAt());
            i.setDurationMs(r.getDurationMs());
            i.setCreatedAt(r.getCreatedAt());
            i.setAttachments(byRun.getOrDefault(r.getId(), List.of()));
            items.add(i);
        }
        return new PageResult<>(result.getTotalElements(), safePage, safeSize, items);
    }

    /**
     * 任务最近一次执行的状态，供导入导出页轮询；完成时附带下载地址。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> status(Long userId, Long taskId) {
        own(userId, taskId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", taskId);
        TaskRun run = runRepository.findByTaskIdInOrderByIdDesc(List.of(taskId)).stream().findFirst().orElse(null);
        if (run == null) {
            out.put("status", "IDLE");
            out.put("progress", 0);
            out.put("finished", true);
            out.put("attachments", List.of());
            return out;
        }
        out.put("runId", run.getId());
        out.put("status", run.getStatus());
        out.put("progress", run.getProgress());
        out.put("message", run.getMessage());
        // 前端展示失败原因依赖 error，缺失会把失败显示成光秃秃的「失败」
        out.put("error", run.getError());
        out.put("result", run.getResult());
        out.put("finished", !LIVE.contains(run.getStatus()));
        List<AttachmentItem> items = attachmentRepository
                .findByRunIdInOrderByIdAsc(List.of(run.getId())).stream().map(this::toItem).toList();
        out.put("attachments", items);
        items.stream().filter(AttachmentItem::isAvailable).findFirst()
                .ifPresent(a -> out.put("downloadUrl", a.getDownloadUrl()));
        return out;
    }

    /** 校验归属并返回可下载的附件记录。 */
    @Transactional(readOnly = true)
    public TaskAttachment attachmentForDownload(Long userId, Long attachmentId) {
        TaskAttachment a = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new BizException("附件不存在或已清理"));
        if (!a.getUserId().equals(userId)) throw new BizException(40300, "无权操作");
        return a;
    }

    /** 文件大小以磁盘为准；产物已被清理时退回登记值，并标记为不可下载。 */
    private AttachmentItem toItem(TaskAttachment a) {
        long live = attachmentFiles.sizeOf(a.getPath());
        Long size = live >= 0 ? live : a.getSizeBytes();
        return new AttachmentItem(a.getId(), a.getName(), size, a.getContentType(), a.getCreatedAt(),
                "/api/task-attachments/" + a.getId() + "/download", live >= 0);
    }

    // ------------------------------------------------------------------ 启动与调度

    /** 调度器取到期的周期任务。 */
    @Transactional(readOnly = true)
    public List<Task> duePeriodicTasks() {
        return taskRepository.findDuePeriodic(LocalDateTime.now());
    }

    /**
     * 执行记录保存策略清理：每轮先删「不在保存范围内」的已结束记录（只留成功/只留失败/不保留），
     * 再删超出保存天数的记录。进行中的记录一律不动；产物附件记录随执行记录外键级联删除。
     *
     * <p>策略按任务逐条判定：任务上设了就用任务的，没设才回落到用户全局策略，
     * 因此一次性导出任务可以只留一条、周期同步任务留久一点。
     */
    @Scheduled(initialDelay = 60_000, fixedDelay = 300_000)
    @Transactional
    public void applyRunRetentionPolicy() {
        for (UserSetting s : settingRepository.findAll()) {
            String globalScope = s.getRunRetentionScope() == null ? "ALL" : s.getRunRetentionScope();
            int globalDays = s.getRunRetentionDays() == null
                    ? SettingsService.DEFAULT_RETENTION_DAYS : s.getRunRetentionDays();
            int removed = 0;
            for (Task t : taskRepository.findByUserId(s.getUserId())) {
                String scope = t.getRunRetentionScope() == null ? globalScope : t.getRunRetentionScope();
                int days = t.getRunRetentionDays() == null ? globalDays : t.getRunRetentionDays();
                removed += applyTaskRetention(t.getId(), scope, days);
            }
            if (removed > 0) {
                log.info("用户 {} 按保存策略（全局 {} / {} 天，任务可覆盖）清理执行记录 {} 条",
                        s.getUserId(), globalScope, globalDays, removed);
            }
        }
    }

    /** 按单个任务的生效策略清理其执行记录，返回删除条数。 */
    private int applyTaskRetention(Long taskId, String scope, int days) {
        int removed = 0;
        if ("NONE".equals(scope)) {
            removed += runRepository.deleteFinishedByTask(taskId);
        } else if ("SUCCESS".equals(scope) || "FAILED".equals(scope)) {
            List<String> drop = "SUCCESS".equals(scope)
                    ? List.of(TaskRun.FAILED, TaskRun.CANCELLED) : List.of(TaskRun.COMPLETED);
            removed += runRepository.deleteByTaskAndStatusIn(taskId, drop);
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(Math.max(days, 1));
        removed += runRepository.deleteFinishedByTaskBefore(taskId, cutoff);
        return removed;
    }

    /**
     * 设置任务级保存策略。两个字段都允许传 null 表示「跟随全局」，
     * 与全局设置共用同一套取值校验，避免前端各自维护一份枚举。
     */
    @Transactional
    public void setRunPolicy(Long userId, Long taskId, String scope, Integer days) {
        Task task = own(userId, taskId);
        if (scope != null && !SettingsService.RETENTION_SCOPES.contains(scope)) {
            throw new BizException("保存范围只能是：全部记录 / 成功记录 / 失败记录 / 不保留");
        }
        if (days != null && (days < 1 || days > SettingsService.MAX_RETENTION_DAYS)) {
            throw new BizException("保存天数需在 1 ~ " + SettingsService.MAX_RETENTION_DAYS + " 天之间");
        }
        task.setRunRetentionScope(scope);
        task.setRunRetentionDays(days);
        taskRepository.save(task);
    }

    /**
     * 进程重启回收：上一个进程被停止时留在 PENDING/RUNNING 的执行线程已不存在，
     * 不修正就会永远显示「进行中」，并挡住该任务的后续调度。
     * 同时为缺少同步任务的账户补建周期任务（老数据或建号时未成功投递的情况）。
     */
    @EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    @Transactional
    public void reconcileAfterRestart() {
        List<TaskRun> live = runRepository.findByStatusIn(LIVE);
        for (TaskRun run : live) {
            run.setStatus(TaskRun.FAILED);
            run.setError("服务重启导致执行中断，可点击「立即执行」重试");
            run.setMessage("失败：服务重启导致执行中断");
            finish(run);
            runRepository.save(run);
        }
        if (!live.isEmpty()) {
            log.info("启动时回收 {} 条中断的后台任务执行记录", live.size());
        }
        for (MailAccount acc : accountRepository.findAll()) {
            ensureAccountSyncTask(acc);
        }
    }

    /**
     * 守护进程巡检：回收「库里仍进行中，但进程内已无存活执行线程」的孤儿记录。
     *
     * <p>{@link #running} 映射是本进程执行线程的权威信号——线程正常结束会在 finally 移除并回写终态，
     * 因此一条 PENDING/RUNNING 记录若在映射中缺失或对应的 Future 已结束，说明工作线程已丢失
     * （线程被硬杀、OOM、线程池关闭丢弃、或事务提交后 submit 前异常）。这类记录若不清理会一直
     * 显示「进行中」，并永久挡住该任务的后续调度（{@link #runInternal} 的 LIVE 拦截）。
     * 与 {@link #reconcileAfterRestart} 互补：后者只处理进程重启，本方法覆盖运行期线程丢失。
     *
     * <p>仅处理建记录超过 {@link #ORPHAN_GRACE} 的记录，避开「事务提交→线程池 submit」的正常排队窗口；
     * Future 仍存活（未 isDone）的长任务不动，交给协议层自身的读超时看门狗处理。
     *
     * @return 本次回收的孤儿记录数
     */
    @Transactional
    public int sweepOrphanRuns() {
        LocalDateTime cutoff = LocalDateTime.now().minus(ORPHAN_GRACE);
        int recovered = 0;
        for (TaskRun run : runRepository.findByStatusIn(LIVE)) {
            if (run.getCreatedAt() != null && run.getCreatedAt().isAfter(cutoff)) continue;
            Future<?> f = running.get(run.getId());
            if (f != null && !f.isDone()) continue; // 线程仍在跑，别动
            running.remove(run.getId());
            // 二次确认：取到列表后到此刻之间可能已正常收尾，只回收仍处进行中的记录
            String current = runRepository.findStatus(run.getId());
            if (!LIVE.contains(current)) continue;
            Task task = taskRepository.findById(run.getTaskId()).orElse(null);
            String reason = "守护进程检测到执行线程已丢失，本轮执行中断，可点击「立即执行」重试";
            run.setStatus(TaskRun.FAILED);
            run.setError(reason);
            run.setMessage("失败：执行线程丢失，已自动回收");
            finish(run);
            runRepository.save(run);
            recovered++;
            notifyFailure(task, reason);
            log.warn("守护进程回收孤儿执行 run={} task={}（原状态 {}）",
                    run.getId(), run.getTaskId(), f == null ? "无线程句柄" : "线程已结束");
        }
        return recovered;
    }
}
