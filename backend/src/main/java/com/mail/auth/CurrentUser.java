package com.mail.auth;

import com.mail.common.BizException;

/**
 * 当前登录用户上下文（ThreadLocal）。由 JwtAuthFilter 写入，业务代码读取。
 */
public final class CurrentUser {

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();

    private CurrentUser() {
    }

    public static void set(Long userId) {
        USER_ID.set(userId);
    }

    public static Long get() {
        return USER_ID.get();
    }

    public static Long requireId() {
        Long id = USER_ID.get();
        if (id == null) {
            throw new BizException(40100, "未登录");
        }
        return id;
    }

    public static void clear() {
        USER_ID.remove();
    }
}