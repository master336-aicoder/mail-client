package com.mail.common;

/**
 * 邮件 flags 位图常量，见《详细设计》3.2。
 */
public final class MailFlags {

    public static final int READ = 1;
    public static final int STARRED = 2;
    public static final int IMPORTANT = 4;
    public static final int HAS_ATTACH = 8;

    private MailFlags() {
    }

    public static boolean has(int flags, int bit) {
        return (flags & bit) != 0;
    }

    public static int set(int flags, int bit, boolean on) {
        return on ? flags | bit : flags & ~bit;
    }
}