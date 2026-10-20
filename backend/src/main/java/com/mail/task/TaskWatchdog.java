package com.mail.task;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 后台任务守护进程：周期性巡检并回收「库里仍进行中、但执行线程已丢失」的孤儿执行记录，
 * 防止其永远显示「进行中」并挡住该任务的后续调度。
 *
 * <p>刻意<b>不用</b> {@code @Scheduled}：Spring 的 {@code @EnableScheduling} 由
 * {@code mail.app.scheduling-enabled} 整体开关控制，关掉调度时所有 {@code @Scheduled} 方法都会停摆；
 * 而手动执行 / 一次性导入导出任务不依赖调度器、照常运行，同样可能产生孤儿记录需要守护。
 * 因此这里用独立的 {@link ScheduledExecutorService}（守护线程），只要进程活着就一直在跑。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskWatchdog {

    /** 巡检间隔（秒）。 */
    public static final int SWEEP_INTERVAL_SEC = 60;
    /** 进程就绪后首轮延迟，给启动回收与首批任务留出时间。 */
    private static final int INITIAL_DELAY_SEC = 30;

    private final TaskService taskService;

    private ScheduledExecutorService executor;
    private volatile boolean active;
    private volatile LocalDateTime lastSweepAt;
    private final AtomicLong totalRecovered = new AtomicLong();

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "task-watchdog");
            t.setDaemon(true);
            return t;
        };
        executor = Executors.newSingleThreadScheduledExecutor(tf);
        executor.scheduleWithFixedDelay(this::sweep,
                INITIAL_DELAY_SEC, SWEEP_INTERVAL_SEC, TimeUnit.SECONDS);
        active = true;
        log.info("后台任务守护进程已启动：每 {} 秒巡检一次孤儿执行", SWEEP_INTERVAL_SEC);
    }

    private void sweep() {
        try {
            int recovered = taskService.sweepOrphanRuns();
            lastSweepAt = LocalDateTime.now();
            if (recovered > 0) {
                totalRecovered.addAndGet(recovered);
                log.info("守护进程本轮回收 {} 条孤儿执行记录（累计 {}）", recovered, totalRecovered.get());
            }
        } catch (Exception e) {
            log.warn("守护进程巡检异常（下轮继续）: {}", e.getMessage());
        }
    }

    /** 立即执行一次巡检，返回本轮回收数（供管理端「立即巡检」与验证用）。 */
    public long sweepNow() {
        int recovered = taskService.sweepOrphanRuns();
        lastSweepAt = LocalDateTime.now();
        if (recovered > 0) totalRecovered.addAndGet(recovered);
        return recovered;
    }

    public boolean isActive() {
        return active;
    }

    public LocalDateTime getLastSweepAt() {
        return lastSweepAt;
    }

    public long getTotalRecovered() {
        return totalRecovered.get();
    }

    @PreDestroy
    public void stop() {
        active = false;
        if (executor != null) executor.shutdownNow();
    }
}
