package com.workbuddy.pbccrc.job;

import com.workbuddy.pbccrc.config.PbccrcProperties;
import com.workbuddy.pbccrc.dao.ReportMapper;
import com.workbuddy.pbccrc.model.entity.Report;
import com.workbuddy.pbccrc.service.AlertNotifier;
import com.workbuddy.pbccrc.service.ParseService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 解析补偿定时任务。
 *
 * <p>抢到主节点分布式锁后扫描三类待处理报告：心跳超时的卡死报告、可重试的失败报告
 * 重置为待解析并重投（重投走同一解析流程，天然幂等）；超过最大重试次数的死信报告
 * 只做预警，等待人工处理。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
@Slf4j
@Component
public class CompensationJob {

    private final ReportMapper reportMapper;
    private final ParseService parseService;
    private final AlertNotifier alert;
    private final PbccrcProperties properties;
    private final RedissonClient redissonClient;

    /**
     * 构造方法，由 Spring 注入依赖。
     *
     * @param reportMapper    报告主表数据访问对象
     * @param parseService    解析服务
     * @param alert           预警通道
     * @param properties      业务配置
     * @param redissonClient  Redis 客户端，用于主节点锁（必需依赖）
     */
    public CompensationJob(ReportMapper reportMapper, ParseService parseService,
                           AlertNotifier alert, PbccrcProperties properties,
                           RedissonClient redissonClient) {
        this.reportMapper = reportMapper;
        this.parseService = parseService;
        this.alert = alert;
        this.properties = properties;
        this.redissonClient = redissonClient;
    }

    /**
     * 定时扫描并执行补偿。
     *
     * <p>仅抢到锁的节点执行：依次处理卡死报告、可重试报告并发出死信预警；
     * 未抢到锁的节点直接返回，避免多节点重复补偿。</p>
     */
    @Scheduled(cron = "${pbccrc.parse.dead-letter-scan-cron}")
    public void scan() {

        // Redis 为必需依赖：仅抢到主节点锁的节点执行补偿，避免多节点重复补偿
        RLock lock = redissonClient.getLock("pbccrc:compensate:lock");
        if (!lock.tryLock()) {
            log.warn("定时扫描并执行补偿抢锁失败~");
            return;
        }
        try {
            int timeout = properties.getParse().getHeartbeatTimeoutMinutes();
            int maxRetry = properties.getParse().getMaxRetry();

            // 心跳超时判定为卡死，重置后重投
            for (Integer id : reportMapper.findStuck(timeout)) {
                if (reportMapper.resetToPending(id) > 0) {
                    log.info("补偿: 卡死报告 {} 重置并重投", id);
                    parseService.parseAsync(id);
                }
            }
            // 未超重试上限的失败报告，重置后重投
            for (Integer id : reportMapper.findRetryable(maxRetry)) {
                if (reportMapper.resetToPending(id) > 0) {
                    log.info("补偿: 可重试报告 {} 重置并重投", id);
                    parseService.parseAsync(id);
                }
            }
            // 超过重试上限进入死信，仅预警
            List<Report> dead = reportMapper.findDeadLetters(maxRetry);
            for (Report r : dead) {
                alert.notify("征信报告进入死信", "reportId=" + r.getId()
                        + ", 客户=" + r.getCustomerName() + ", 原因=" + r.getErrorMsg());
            }
            if (!dead.isEmpty()) {
                log.warn("死信清单: {} 份待人工处理", dead.size());
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
