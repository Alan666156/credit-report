package com.workbuddy.pbccrc.service;

/**
 * 征信报告异步解析服务。
 *
 * <p>负责任务互斥与状态流转：以 Redis 分布式锁（必需依赖）保证同一报告同一时刻仅一个
 * 节点解析；通过 CAS 抢占保证状态幂等；解析期间周期性上报心跳；解析成功写库，失败则
 * 标记失败并触发预警，由补偿任务兜底重试。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
public interface ParseService {

    /**
     * 异步触发报告解析。
     *
     * <p>先加 Redis 分布式锁再执行解析（Redis 为必需依赖），避免多节点重复解析同一报告。</p>
     *
     * @param reportId 报告主键
     */
    void parseAsync(Integer reportId);
}
