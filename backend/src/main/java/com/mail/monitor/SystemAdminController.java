package com.mail.monitor;

import com.mail.common.ApiResponse;
import com.mail.common.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 系统状态监控与预警配置接口（仅管理员，/api/admin/** 已由 SecurityConfig 限制 ROLE_ADMIN）。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class SystemAdminController {

    private final SystemMetricsService metricsService;
    private final SystemAlertConfigRepository configRepository;
    private final SystemAlertScheduler alertScheduler;

    @GetMapping("/system-status")
    public ApiResponse<SystemStatus> systemStatus() {
        return ApiResponse.ok(metricsService.collect());
    }

    @GetMapping("/system-alert-config")
    public ApiResponse<SystemAlertConfig> alertConfig() {
        return ApiResponse.ok(load());
    }

    @PutMapping("/system-alert-config")
    public ApiResponse<SystemAlertConfig> updateAlertConfig(@RequestBody Map<String, Object> body) {
        SystemAlertConfig cfg = load();
        Object enabled = body.get("enabled");
        if (enabled != null) cfg.setEnabled(Boolean.parseBoolean(String.valueOf(enabled)));
        cfg.setCpuThresholdPct(intField(body, "cpuThresholdPct", cfg.getCpuThresholdPct(), 1, 100, "CPU 阈值"));
        cfg.setMemThresholdPct(intField(body, "memThresholdPct", cfg.getMemThresholdPct(), 1, 100, "内存阈值"));
        cfg.setDiskThresholdPct(intField(body, "diskThresholdPct", cfg.getDiskThresholdPct(), 1, 100, "磁盘阈值"));
        cfg.setCheckIntervalMin(intField(body, "checkIntervalMin", cfg.getCheckIntervalMin(), 1, 1440, "巡检间隔"));
        cfg.setCooldownMin(intField(body, "cooldownMin", cfg.getCooldownMin(), 0, 1440, "告警冷却"));
        return ApiResponse.ok(configRepository.save(cfg));
    }

    /** 立即触发一次预警巡检（忽略 check_interval_min，冷却窗口仍生效）。 */
    @PostMapping("/system-alert-check")
    public ApiResponse<Map<String, Object>> manualAlertCheck() {
        return ApiResponse.ok(alertScheduler.runCheck(true));
    }

    /** 清空三个 last*AlertAt 冷却时间戳，便于运维手动重放告警或测试。 */
    @PostMapping("/system-alert-reset-cooldown")
    public ApiResponse<SystemAlertConfig> resetAlertCooldown() {
        SystemAlertConfig cfg = load();
        cfg.setLastCpuAlertAt(null);
        cfg.setLastMemAlertAt(null);
        cfg.setLastDiskAlertAt(null);
        return ApiResponse.ok(configRepository.save(cfg));
    }

    private SystemAlertConfig load() {
        return configRepository.findById(1L).orElseGet(() -> {
            SystemAlertConfig c = new SystemAlertConfig();
            c.setId(1L);
            return c;
        });
    }

    private static int intField(Map<String, Object> body, String key, int current,
                                int min, int max, String label) {
        Object v = body.get(key);
        if (v == null) return current;
        if (!(v instanceof Number n)) throw new BizException(label + "必须是数字");
        int i = n.intValue();
        if (i < min || i > max) {
            throw new BizException(label + "需在 " + min + " ~ " + max + " 之间");
        }
        return i;
    }
}
