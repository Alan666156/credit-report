package com.workbuddy.pbccrc.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应包装，所有接口返回 code/message/data 三段结构。
 *
 * @param <T> 业务数据类型
 * @author Alan
 * @date 2026-11-17
 */
@Data
public class Result<T> implements Serializable {
    private int code;
    private String message;
    private T data;

    /**
     * 构造成功响应并携带数据。
     *
     * @param data 业务数据
     * @param <T>  业务数据类型
     * @return 成功响应
     */
    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.setCode(ResultCode.SUCCESS.getCode());
        r.setMessage(ResultCode.SUCCESS.getMessage());
        r.setData(data);
        return r;
    }

    /**
     * 构造无数据的成功响应。
     *
     * @param <T> 业务数据类型
     * @return 成功响应
     */
    public static <T> Result<T> ok() {
        return ok(null);
    }

    /**
     * 使用返回码默认提示构造失败响应。
     *
     * @param rc  返回码
     * @param <T> 业务数据类型
     * @return 失败响应
     */
    public static <T> Result<T> fail(ResultCode rc) {
        return fail(rc, rc.getMessage());
    }

    /**
     * 使用返回码与自定义提示构造失败响应。
     *
     * @param rc      返回码
     * @param message 自定义提示信息
     * @param <T>     业务数据类型
     * @return 失败响应
     */
    public static <T> Result<T> fail(ResultCode rc, String message) {
        Result<T> r = new Result<>();
        r.setCode(rc.getCode());
        r.setMessage(message);
        return r;
    }
}
