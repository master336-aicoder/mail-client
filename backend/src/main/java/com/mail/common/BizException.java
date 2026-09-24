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

    public int getCode() {
        return code;
    }
}