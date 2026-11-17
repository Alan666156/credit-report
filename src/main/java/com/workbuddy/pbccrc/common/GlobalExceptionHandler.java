package com.workbuddy.pbccrc.common;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理器。
 *
 * <p>将业务异常、参数校验异常与系统异常统一转换为 {@link Result} 响应：
 * 业务异常透传返回码与提示，参数校验异常归为参数错误，其余归为系统异常并记录日志。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理业务异常。
     *
     * @param e 业务异常
     * @return 携带对应返回码与提示的失败响应
     */
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e) {
        log.warn("业务异常: {}", e.getMessage());
        return Result.fail(e.getResultCode(), e.getMessage());
    }

    /**
     * 处理请求体参数校验异常，拼接各字段错误提示。
     *
     * @param e 参数校验异常
     * @return 参数错误响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage).collect(Collectors.joining("; "));
        return Result.fail(ResultCode.PARAM_ERROR, msg);
    }

    /**
     * 处理约束校验异常。
     *
     * @param e 约束校验异常
     * @return 参数错误响应
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraint(ConstraintViolationException e) {
        return Result.fail(ResultCode.PARAM_ERROR, e.getMessage());
    }

    /**
     * 处理其余系统异常。
     *
     * @param e 系统异常
     * @return 系统异常响应
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        log.error("系统异常", e);
        return Result.fail(ResultCode.SYSTEM_ERROR);
    }
}
