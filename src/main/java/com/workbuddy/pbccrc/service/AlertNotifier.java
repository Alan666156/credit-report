package com.workbuddy.pbccrc.service;

/**
 * 预警通道，只负责上报预警信息，不修改业务数据。
 *
 * <p>默认实现为日志预警，后续接入钉钉、邮件等通道时实现本接口即可。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
public interface AlertNotifier {

    /**
     * 发送一条预警。
     *
     * @param title  预警标题
     * @param detail 预警详情
     */
    void notify(String title, String detail);
}
