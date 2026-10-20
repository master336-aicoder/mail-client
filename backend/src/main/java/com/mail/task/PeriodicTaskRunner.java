package com.mail.task;

import com.mail.task.domain.Task;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 周期任务调度：到点的任务（nextRunAt 到期）投递一次执行。
 *
 * <p>调度只认任务定义，不关心任务类型，所以账户同步按各自频率轮转，
 * 新增周期任务类型无需改这里。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PeriodicTaskRunner {

    private final TaskService taskService;

    /** 15 秒一轮；账户频率低于此值时按 {@link TaskService#MIN_INTERVAL_SEC} 兜底。 */
    @Scheduled(fixedDelay = 15_000)
    public void poll() {
        List<Task> due;
        try {
            due = taskService.duePeriodicTasks();
        } catch (Exception e) {
            log.warn("周期任务扫描失败: {}", e.getMessage());
            return;
        }
        for (Task t : due) {
            try {
                taskService.runScheduled(t.getId());
            } catch (Exception e) {
                // 单个任务投递失败不能影响其它账户，下一轮还会再试
                log.warn("周期任务 {} 投递失败: {}", t.getId(), e.getMessage());
            }
        }
    }
}
