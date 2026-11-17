package com.workbuddy.pbccrc.service.impl;

import com.workbuddy.pbccrc.service.AlertNotifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 基于日志的默认预警实现。
 *
 * <p>将预警以 error 级别写入日志；钉钉、邮件等通道后续实现同一接口即可替换。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
@Slf4j
@Component
public class AlertNotifierImpl implements AlertNotifier {

    /**
     * 将预警信息写入 error 日志。
     *
     * @param title  预警标题
     * @param detail 预警详情
     */
    @Override
    public void notify(String title, String detail) {
        log.error("[预警] {} | {}", title, detail);
    }
}
