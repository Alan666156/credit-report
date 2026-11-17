package com.workbuddy.pbccrc.service.impl;

import com.workbuddy.pbccrc.config.PbccrcProperties;
import com.workbuddy.pbccrc.dao.ReportContentMapper;
import com.workbuddy.pbccrc.dao.ReportMapper;
import com.workbuddy.pbccrc.engine.ParseDeadline;
import com.workbuddy.pbccrc.model.entity.Report;
import com.workbuddy.pbccrc.model.entity.ReportContent;
import com.workbuddy.pbccrc.parser.ParseContext;
import com.workbuddy.pbccrc.parser.ParsePipeline;
import com.workbuddy.pbccrc.service.AlertNotifier;
import com.workbuddy.pbccrc.service.ParseResultWriter;
import com.workbuddy.pbccrc.service.ParseService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 征信报告异步解析服务实现。
 *
 * <p>负责任务互斥与状态流转：以 Redis 分布式锁 {@code RLock}（必需依赖）保证同一报告
 * 同一时刻仅一个节点解析；通过 CAS 抢占保证状态幂等；解析期间由看门狗监控「进度
 * 时间戳」，只在阶段推进时刷新心跳，卡死则标记失败并预警（评审 2.3）；解析成功
 * 写库，失败则标记失败并触发预警，由补偿任务兜底重试。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
@Slf4j
@Service
public class ParseServiceImpl implements ParseService {

    private final ReportMapper reportMapper;
    private final ReportContentMapper contentMapper;
    private final ParsePipeline pipeline;
    private final ParseResultWriter writer;
    private final AlertNotifier alert;
    private final PbccrcProperties properties;
    private final RedissonClient redissonClient;

    /**
     * 构造方法，由 Spring 注入依赖。
     *
     * @param reportMapper    报告主表数据访问对象
     * @param contentMapper   报告原文数据访问对象
     * @param pipeline        解析流水线
     * @param writer          解析结果落库器
     * @param alert           预警通道
     * @param properties      业务配置
     * @param redissonClient  Redis 客户端，用于解析互斥锁（必需依赖）
     */
    public ParseServiceImpl(ReportMapper reportMapper, ReportContentMapper contentMapper,
                            ParsePipeline pipeline, ParseResultWriter writer,
                            AlertNotifier alert, PbccrcProperties properties,
                            RedissonClient redissonClient) {
        this.reportMapper = reportMapper;
        this.contentMapper = contentMapper;
        this.pipeline = pipeline;
        this.writer = writer;
        this.alert = alert;
        this.properties = properties;
        this.redissonClient = redissonClient;
    }

    /**
     * 异步触发报告解析。
     *
     * <p>先加 Redis 分布式锁再执行解析（Redis 为必需依赖），避免多节点重复解析同一报告。</p>
     *
     * @param reportId 报告主键
     */
    @Override
    @Async("parseTaskExecutor")
    public void parseAsync(Integer reportId) {
        RLock lock = redissonClient.getLock("pbccrc:parse:" + reportId);
        try {
            lock.lock(30, TimeUnit.MINUTES);
            doParse(reportId);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 执行解析的核心逻辑。
     *
     * <p>先用 CAS 抢占报告状态，抢占失败说明已被其它节点处理；随后启动看门狗虚拟线程，
     * 仅在「距上次阶段推进超过超时阈值」时判定卡死。心跳不再是定时无条件刷新，而是由
     * 解析流水线的进度回调在每阶段推进后写入，因此真正卡住的解析（正则灾难性回溯、
     * 连接池耗尽等）会导致进度时间戳停滞，从而被看门狗和补偿任务发现（评审 2.3）。
     * 已判超时的报告即使迟到完成也不再落库，避免覆盖失败状态。</p>
     *
     * @param reportId 报告主键
     */
    private void doParse(Integer reportId) {
        // CAS 抢占：仅待解析状态可抢到，保证同一报告不被重复处理
        if (reportMapper.claim(reportId) == 0) {
            log.info("report {} 已被其它节点抢占或非待解析状态，跳过", reportId);
            return;
        }

        int timeoutMinutes = properties.getParse().getParseTimeoutMinutes();
        long timeoutNanos = TimeUnit.MINUTES.toNanos(timeoutMinutes);
        ParseDeadline deadline = new ParseDeadline();
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicBoolean timedOut = new AtomicBoolean(false);

        // 看门狗：周期检查「进度时间戳」是否停滞；停滞即标记失败并预警，使补偿可介入
        Thread watchdog = Thread.ofVirtual().unstarted(() -> {
            while (running.get()) {
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (running.get() && deadline.isExpired(timeoutNanos)) {
                    timedOut.set(true);
                    String msg = "解析超时：超过 " + timeoutMinutes + " 分钟无阶段推进";
                    log.error("report {} {}", reportId, msg);
                    try {
                        reportMapper.markFailed(reportId, msg);
                        alert.notify("征信报告解析超时", "reportId=" + reportId + ", " + msg);
                    } catch (RuntimeException ignored) {
                        // 标记失败失败不影响退出，由下一次补偿扫描兜底
                    }
                    return;
                }
            }
        });
        watchdog.start();

        // 进度回调：只有阶段真正推进才刷新心跳（进度时间戳）并重置超时计时
        Runnable onProgress = () -> {
            deadline.markProgress();
            try {
                reportMapper.heartbeat(reportId);
            } catch (RuntimeException ignored) {
                // 心跳写入失败不影响解析本身
            }
        };

        try {
            Report report = reportMapper.findById(reportId);
            ReportContent content = contentMapper.findByReportId(reportId);
            if (report == null || content == null || content.getContent() == null) {
                throw new IllegalStateException("报告或原文不存在: " + reportId);
            }
            ParseContext ctx = new ParseContext();
            ctx.setReportId(reportId);
            ctx.setHtml(content.getContent());
            ctx.setCustomerIdCard(report.getCustomerIdCard());
            ctx.setCustomerName(report.getCustomerName());
            ctx.setAppNo(report.getAppNo());
            ctx.setContentHash(report.getContentHash());

            pipeline.parse(ctx, onProgress);
            if (timedOut.get()) {
                // 已被看门狗判超时，禁止迟到完成落库覆盖失败状态
                log.warn("report {} 已完成解析但此前已判超时，放弃落库", reportId);
                return;
            }
            writer.write(ctx);
            log.info("report {} 解析入库完成, 明细={}", reportId,
                    ctx.getCreditCards().size() + ctx.getLoans().size() + ctx.getQueryRecords().size());
        } catch (Exception e) {
            if (!timedOut.get()) {
                log.error("report {} 解析失败", reportId, e);
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                reportMapper.markFailed(reportId, msg.length() > 1000 ? msg.substring(0, 1000) : msg);
                alert.notify("征信报告解析失败", "reportId=" + reportId + ", 原因=" + msg);
            }
        } finally {
            running.set(false);
            watchdog.interrupt();
        }
    }
}
