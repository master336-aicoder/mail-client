package com.mail.account;

import com.mail.account.domain.UserSetting;
import com.mail.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SettingsService settingsService;

    @GetMapping
    public ApiResponse<UserSetting> get() {
        return ApiResponse.ok(settingsService.get());
    }

    @PutMapping
    public ApiResponse<UserSetting> update(@RequestBody Map<String, Object> body) {
        Object v = body.get("defaultSyncIntervalSec");
        int interval = v == null ? SettingsService.DEFAULT_INTERVAL_SEC
                : (v instanceof Number n ? n.intValue() : SettingsService.DEFAULT_INTERVAL_SEC);
        Object scope = body.get("runRetentionScope");
        Object days = body.get("runRetentionDays");
        Object theme = body.get("theme");
        return ApiResponse.ok(settingsService.update(
                interval,
                scope == null ? null : String.valueOf(scope),
                days == null ? null : (days instanceof Number n ? n.intValue() : null),
                theme == null ? null : String.valueOf(theme)));
    }
}