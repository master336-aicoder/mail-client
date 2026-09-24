package com.mail.auth;

import com.mail.common.BizException;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 无状态认证接口的内存固定窗口限流器（单机足够）。按 {@code ip|scope|key} 维度节流，
 * 抑制登录暴力破解与注册用户名枚举。超限抛 {@link BizException}(40029)。
 */
@Component
public class LoginThrottle {

    private static final long WINDOW_MS = 15 * 60 * 1000L;
    private static final int MAX_ATTEMPTS = 10;
    private static final int MAX_ENTRIES = 100_000;

    private static final class Window {
        int count;
        long resetAt;
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /** 记录一次失败尝试并在超限时拒绝。 */
    public void guard(String bucket, String identity) {
        String key = bucket + "|" + (identity == null ? "" : identity.toLowerCase());
        long now = System.currentTimeMillis();
        evictIfNeeded(now);
        Window w = windows.computeIfAbsent(key, k -> {
            Window n = new Window();
            n.resetAt = now + WINDOW_MS;
            return n;
        });
        synchronized (w) {
            if (now > w.resetAt) {
                w.count = 0;
                w.resetAt = now + WINDOW_MS;
            }
            w.count++;
            if (w.count > MAX_ATTEMPTS) {
                throw new BizException(40029, "尝试过于频繁，请稍后再试");
            }
        }
    }

    /** 成功时清除计数，避免正常用户被历史失败误伤。 */
    public void reset(String bucket, String identity) {
        windows.remove(bucket + "|" + (identity == null ? "" : identity.toLowerCase()));
    }

    private void evictIfNeeded(long now) {
        if (windows.size() < MAX_ENTRIES) return;
        Iterator<Map.Entry<String, Window>> it = windows.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Window> e = it.next();
            if (now > e.getValue().resetAt) it.remove();
        }
    }
}
