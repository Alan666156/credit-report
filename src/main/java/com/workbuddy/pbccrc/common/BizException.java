package com.workbuddy.pbccrc.common;

import lombok.Getter;

/**
 * 业务异常，携带统一返回码与提示信息。
 *
 * <p>用于服务层显式抛出可预期的业务错误，由全局异常处理器转为统一响应。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
@Getter
public class BizException extends RuntimeException {
    private final ResultCode resultCode;

    /**
     * 使用返回码的默认提示构造异常。
     *
     * @param resultCode 返回码
     */
    public BizException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.resultCode = resultCode;
    }

    /**
     * 使用返回码与自定义提示构造异常。
     *
     * @param resultCode 返回码
     * @param message    自定义提示信息
     */
    public BizException(ResultCode resultCode, String message) {
        super(message);
        this.resultCode = resultCode;
    }
}
