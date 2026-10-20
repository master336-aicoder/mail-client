package com.mail.account;

import com.mail.account.domain.UserSetting;
import com.mail.account.domain.UserSettingRepository;
import com.mail.auth.CurrentUser;
import com.mail.common.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 用户级设置。
 */
@Service
@RequiredArgsConstructor
public class SettingsService {

    private final UserSettingRepository settingRepository;

    public static final int DEFAULT_INTERVAL_SEC = 60;
    /** 执行记录保存范围取值 */
    public static final List<String> RETENTION_SCOPES = List.of("ALL", "SUCCESS", "FAILED", "NONE");
    public static final int DEFAULT_RETENTION_DAYS = 7;
    public static final int MAX_RETENTION_DAYS = 3650;
    /** 外观主题取值 */
    public static final List<String> THEMES = List.of("auto", "light", "dark");

    /** 执行记录保存策略：范围 + 天数。 */
    public record Retention(String scope, int days) {
    }

    @Transactional(readOnly = true)
    public UserSetting get() {
        Long userId = CurrentUser.requireId();
        return settingRepository.findById(userId).orElseGet(() -> {
            UserSetting d = new UserSetting();
            d.setUserId(userId);
            d.setDefaultSyncIntervalSec(DEFAULT_INTERVAL_SEC);
            return d;
        });
    }

    @Transactional(readOnly = true)
    public int defaultSyncIntervalSec(Long userId) {
        return settingRepository.findById(userId)
                .map(s -> s.getDefaultSyncIntervalSec() == null
                        ? DEFAULT_INTERVAL_SEC : s.getDefaultSyncIntervalSec())
                .orElse(DEFAULT_INTERVAL_SEC);
    }

    /** 保存策略供清理任务按 userId 读取（调度线程没有登录上下文）。 */
    @Transactional(readOnly = true)
    public Retention runRetention(Long userId) {
        return settingRepository.findById(userId)
                .map(s -> new Retention(
                        s.getRunRetentionScope() == null ? "ALL" : s.getRunRetentionScope(),
                        s.getRunRetentionDays() == null ? DEFAULT_RETENTION_DAYS : s.getRunRetentionDays()))
                .orElseGet(() -> new Retention("ALL", DEFAULT_RETENTION_DAYS));
    }

    @Transactional
    public UserSetting update(int defaultSyncIntervalSec, String runRetentionScope, Integer runRetentionDays) {
        return update(defaultSyncIntervalSec, runRetentionScope, runRetentionDays, null);
    }

    @Transactional
    public UserSetting update(int defaultSyncIntervalSec, String runRetentionScope, Integer runRetentionDays,
                              String theme) {
        Long userId = CurrentUser.requireId();
        int interval = Math.max(15, Math.min(defaultSyncIntervalSec, 30 * 24 * 3600));
        if (runRetentionScope != null && !RETENTION_SCOPES.contains(runRetentionScope)) {
            throw new BizException("保存范围只能是：全部记录 / 成功记录 / 失败记录 / 不保留");
        }
        if (runRetentionDays != null
                && (runRetentionDays < 1 || runRetentionDays > MAX_RETENTION_DAYS)) {
            throw new BizException("保存天数需在 1 ~ " + MAX_RETENTION_DAYS + " 天之间");
        }
        if (theme != null && !THEMES.contains(theme)) {
            throw new BizException("主题只能是：跟随系统 / 浅色 / 深色");
        }
        UserSetting s = settingRepository.findById(userId).orElseGet(() -> {
            UserSetting n = new UserSetting();
            n.setUserId(userId);
            return n;
        });
        s.setDefaultSyncIntervalSec(interval);
        if (runRetentionScope != null) s.setRunRetentionScope(runRetentionScope);
        if (runRetentionDays != null) s.setRunRetentionDays(runRetentionDays);
        if (theme != null) s.setTheme(theme);
        return settingRepository.save(s);
    }
}
