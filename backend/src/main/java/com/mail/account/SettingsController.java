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
        int interval = v == null ? SettingsService.DEFAULT_INTERVAL_SEC : ((Number) v).intValue();
        return ApiResponse.ok(settingsService.update(interval));
    }
}