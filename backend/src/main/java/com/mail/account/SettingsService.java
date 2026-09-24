package com.mail.account;

import com.mail.account.domain.UserSetting;
import com.mail.account.domain.UserSettingRepository;
import com.mail.auth.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户级设置。
 */
@Service
@RequiredArgsConstructor
public class SettingsService {

    private final UserSettingRepository settingRepository;

    public static final int DEFAULT_INTERVAL_SEC = 60;

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

    @Transactional
    public UserSetting update(int defaultSyncIntervalSec) {
        Long userId = CurrentUser.requireId();
        int interval = Math.max(15, Math.min(defaultSyncIntervalSec, 24 * 3600));
        UserSetting s = settingRepository.findById(userId).orElseGet(() -> {
            UserSetting n = new UserSetting();
            n.setUserId(userId);
            return n;
        });
        s.setDefaultSyncIntervalSec(interval);
        return settingRepository.save(s);
    }
}