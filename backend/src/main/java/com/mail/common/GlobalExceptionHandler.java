package com.mail.common;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import lombok.extern.slf4j.Slf4j;

import java.util.stream.Collectors;

/**
 * 全局异常处理，保证接口始终返回统一结构。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ApiResponse<Void> handleBiz(BizException e) {
        return ApiResponse.error(e.getCode(), e.getMessage());
    }

    /** 参数越界（如分页 page<=0 / size 过大）统一翻译成中文业务码。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<Void> handleIllegalArg(IllegalArgumentException e) {
        return ApiResponse.error(40001, "参数不合法：" + e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<Void> handleValidation(MethodArgumentNotValidException e) {
        // 按字段名排序，保证同一批错误每次返回顺序一致，多字段时用户能对上号
        // 校验消息本身已含中文字段名；按字段排序保证多字段时顺序稳定
        String msg = e.getBindingResult().getFieldErrors().stream()
                .sorted(java.util.Comparator.comparing(FieldError::getField))
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        return ApiResponse.error(40001, msg.isEmpty() ? "提交内容不符合要求，请检查后重试" : msg);
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Void> handleDenied(AccessDeniedException e) {
        return ApiResponse.error(40300, "无权访问");
    }

    /**
     * 请求体无法反序列化（非法 JSON、编码错误、字段类型不符）。
     * 不拦截会被兜底成「网络不可达：Invalid UTF-8 …」，把客户端输入问题说成网络故障。
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ApiResponse<Void> handleUnreadable(org.springframework.http.converter.HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return ApiResponse.error(40001, "提交的内容无法解析：请确认表单数据完整后重试（若粘贴了特殊字符，请清空后重新输入）");
    }

    /** 路径/查询参数类型不符（如 id 传了非数字）。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ApiResponse<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型不符 {}: {}", e.getName(), e.getMessage());
        return ApiResponse.error(40001, "参数「" + e.getName() + "」格式不正确，请刷新页面后重试");
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleOther(Exception e) {
        // 原始异常只进日志；响应给用户的必须是中文可读、可行动的信息。
        log.warn("未处理异常 {}: {}", e.getClass().getName(), e.getMessage(), e);
        return ApiResponse.error(50000, MailErrors.explain(e));
    }
}