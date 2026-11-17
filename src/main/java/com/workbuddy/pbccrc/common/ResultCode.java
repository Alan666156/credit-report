package com.workbuddy.pbccrc.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 统一返回码枚举，定义业务码与对应提示信息。
 *
 * @author Alan
 * @date 2026-11-17
 */
@Getter
@AllArgsConstructor
public enum ResultCode {
    SUCCESS(0, "成功"),
    PARAM_ERROR(400, "参数错误"),
    UNAUTHORIZED(401, "未授权"),
    NOT_FOUND(404, "记录不存在"),
    DUPLICATE_SUBMIT(409, "该客户7天内已提交过报告"),
    PARSE_FAILED(500, "解析失败"),
    SYSTEM_ERROR(500, "系统异常");

    private final int code;
    private final String message;
}
