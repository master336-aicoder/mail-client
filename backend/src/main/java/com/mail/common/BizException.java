package com.mail.common;

/**
 * 业务异常。code 为业务错误码（大于 0），message 面向用户。
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(String message) {
        this(40000, message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 带上原始协议异常：中文提示给用户看，原始异常留给上层判定失败类型
     * （如 {@link MailErrors#isAuthRejected} 据此决定收信任务是否退避）。
     */
    public BizException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}