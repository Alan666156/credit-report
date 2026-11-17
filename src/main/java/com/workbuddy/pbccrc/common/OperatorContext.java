package com.workbuddy.pbccrc.common;

/**
 * 操作人上下文，用于在一次请求内传递经鉴权确认的操作人身份。
 *
 * <p>评审 2.5：旧实现的操作人完全由客户端在请求体里自报，查询流水等于没有留痕。
 * 鉴权过滤器校验通过后把可信身份写入本上下文，业务层优先从此处取操作人，
 * 取不到时（如未启用鉴权）才回退到请求体中的值，保持向后兼容。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
public final class OperatorContext {

    private static final ThreadLocal<String> OPERATOR = new ThreadLocal<>();

    private OperatorContext() {
    }

    /**
     * 设置当前请求的操作人身份。
     *
     * @param operator 经鉴权确认的操作人
     */
    public static void set(String operator) {
        OPERATOR.set(operator);
    }

    /**
     * 取当前请求的操作人身份。
     *
     * @return 操作人，未设置返回 null
     */
    public static String get() {
        return OPERATOR.get();
    }

    /**
     * 从上下文取操作人，取不到时回退到调用方提供的默认（请求体）值。
     *
     * @param fallback 请求体中的操作人
     * @return 可信操作人优先，否则返回 fallback
     */
    public static String getOrDefault(String fallback) {
        String operator = OPERATOR.get();
        return operator == null || operator.isBlank() ? fallback : operator;
    }

    /**
     * 清除当前请求的操作人，防止线程池复用导致身份串用。
     */
    public static void clear() {
        OPERATOR.remove();
    }
}
