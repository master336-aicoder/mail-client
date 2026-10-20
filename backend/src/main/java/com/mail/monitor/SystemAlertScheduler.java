package com.mail.monitor;

import com.mail.notification.NotificationService;
import com.mail.notification.domain.Notification;
import com.mail.user.domain.User;
import com.mail.user.domain.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 系统资源预警巡检：每 60 秒轮询一次，仅在 enabled 开启且距上次告警超过 check_interval_min 时采样判定。
 * CPU / 内存 / 磁盘任一超过阈值即向全体管理员扇出 SYSTEM_ALERT 站内信，同类告警按 cooldown_min 去重。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemAlertScheduler {

    private final SystemAlertConfigRepository configRepository;
    private final SystemMetricsService metricsService;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    /** 上次实际采样的时刻（进程内，无需持久化）。 */
    private LocalDateTime lastCheckAt;

    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    @Transactional
    public void check() {
        runCheck(false);
    }

    /**
     * 手动/定时共用巡检。force=true 忽略 check_interval_min 节流（冷却窗口仍生效），
     * 供管理端「立即检查一次」按钮调用。返回本轮触发的告警项与被冷却抑制的项，供前端展示。
     */
    @Transactional
    public java.util.Map<String, Object> runCheck(boolean force) {
        SystemAlertConfig cfg = configRepository.findById(1L).orElse(null);
        java.util.List<java.util.Map<String, Object>> fired = new java.util.ArrayList<>();
        java.util.List<java.util.Map<String, Object>> suppressed = new java.util.ArrayList<>();
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("fired", fired);
        out.put("suppressed", suppressed);
        if (cfg == null) { out.put("enabled", false); return out; }
        out.put("enabled", cfg.isEnabled());
        if (!cfg.isEnabled()) { out.put("message", "预警未启用"); return out; }

        LocalDateTime now = LocalDateTime.now();
        if (!force && lastCheckAt != null
                && Duration.between(lastCheckAt, now).toMinutes() < cfg.getCheckIntervalMin()) {
            out.put("message", "距上次检查间隔不足，已跳过");
            return out;
        }
        lastCheckAt = now;

        SystemStatus s = metricsService.collect();
        out.put("status", s);

        // CPU
        Double cpuPct = max(s.getCpu() == null ? null : s.getCpu().getSystemLoadPct(),
                s.getCpu() == null ? null : s.getCpu().getProcessLoadPct());
        if (cpuPct != null && cpuPct >= cfg.getCpuThresholdPct()) {
            if (inCooldown(cfg.getLastCpuAlertAt(), now, cfg.getCooldownMin())) {
                suppressed.add(item("cpu", cpuPct, cfg.getCpuThresholdPct(), "冷却中"));
            } else {
                String title = "CPU 使用率预警";
                String content = String.format("当前 CPU 使用率 %.1f%%，已超过阈值 %d%%。",
                        cpuPct, cfg.getCpuThresholdPct());
                int n = fanOut(title, content);
                cfg.setLastCpuAlertAt(now);
                fired.add(item("cpu", cpuPct, cfg.getCpuThresholdPct(), title + " → " + n + " 位管理员"));
            }
        }

        // 内存
        Integer memPct = s.getMemory() == null ? null : s.getMemory().getUsedPct();
        if (memPct != null && memPct >= cfg.getMemThresholdPct()) {
            if (inCooldown(cfg.getLastMemAlertAt(), now, cfg.getCooldownMin())) {
                suppressed.add(item("mem", memPct, cfg.getMemThresholdPct(), "冷却中"));
            } else {
                String title = "内存使用率预警";
                String content = String.format("当前系统内存使用率 %d%%，已超过阈值 %d%%。",
                        memPct, cfg.getMemThresholdPct());
                int n = fanOut(title, content);
                cfg.setLastMemAlertAt(now);
                fired.add(item("mem", memPct, cfg.getMemThresholdPct(), title + " → " + n + " 位管理员"));
            }
        }

        // 磁盘（取最高使用率）
        SystemStatus.Disk worst = null;
        if (s.getDisks() != null) {
            for (SystemStatus.Disk d : s.getDisks()) {
                if (d.getUsedPct() != null && (worst == null || d.getUsedPct() > worst.getUsedPct())) {
                    worst = d;
                }
            }
        }
        if (worst != null && worst.getUsedPct() >= cfg.getDiskThresholdPct()) {
            if (inCooldown(cfg.getLastDiskAlertAt(), now, cfg.getCooldownMin())) {
                suppressed.add(item("disk", worst.getUsedPct(), cfg.getDiskThresholdPct(), "冷却中"));
            } else {
                String title = "磁盘空间预警";
                String content = String.format("磁盘 %s 使用率 %d%%，已超过阈值 %d%%（剩余 %.1f GB）。",
                        worst.getPath(), worst.getUsedPct(), cfg.getDiskThresholdPct(),
                        worst.getUsableBytes() / 1024.0 / 1024.0 / 1024.0);
                int n = fanOut(title, content);
                cfg.setLastDiskAlertAt(now);
                fired.add(item("disk", worst.getUsedPct(), cfg.getDiskThresholdPct(), title + " → " + n + " 位管理员"));
            }
        }

        configRepository.save(cfg);
        return out;
    }

    private static java.util.Map<String, Object> item(String metric, Number value, Number threshold, String note) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("metric", metric);
        m.put("value", value);
        m.put("threshold", threshold);
        m.put("note", note);
        return m;
    }

    /** 扇出站内信给所有 ADMIN，返回收到通知的管理员数量。 */
    private int fanOut(String title, String content) {
        List<User> admins = userRepository.findByRole("ADMIN");
        if (admins.isEmpty()) {
            log.warn("触发系统预警 [{}] 但没有 ADMIN 用户可通知", title);
            return 0;
        }
        for (User a : admins) {
            notificationService.notify(a.getId(), Notification.SYSTEM_ALERT, title, content, "/admin");
        }
        log.info("系统预警 [{}] 已通知 {} 位管理员", title, admins.size());
        return admins.size();
    }

    /** 冷却期内返回 true：距上次告警不足 cooldownMin 分钟。 */
    private static boolean inCooldown(LocalDateTime last, LocalDateTime now, int cooldownMin) {
        return last != null && Duration.between(last, now).toMinutes() < cooldownMin;
    }

    private static Double max(Double a, Double b) {
        if (a == null) return b;
        if (b == null) return a;
        return a >= b ? a : b;
    }
}
