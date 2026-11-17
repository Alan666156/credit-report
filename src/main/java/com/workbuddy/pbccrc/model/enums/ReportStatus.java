package com.workbuddy.pbccrc.model.enums;

/**
 * 报告解析状态机常量。
 *
 * <p>定义报告在异步解析生命周期中的四种状态：
 * 待解析（PENDING）、解析中（PARSING）、成功（SUCCESS）、失败（FAILED）。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
public final class ReportStatus {
    public static final String PENDING = "PENDING";
    public static final String PARSING = "PARSING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";

    private ReportStatus() {
    }
}
