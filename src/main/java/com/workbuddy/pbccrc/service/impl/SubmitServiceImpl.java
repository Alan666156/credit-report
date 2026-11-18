package com.workbuddy.pbccrc.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.workbuddy.pbccrc.common.BizException;
import com.workbuddy.pbccrc.common.OperatorContext;
import com.workbuddy.pbccrc.common.ResultCode;
import com.workbuddy.pbccrc.config.PbccrcProperties;
import com.workbuddy.pbccrc.dao.ReportContentMapper;
import com.workbuddy.pbccrc.dao.ReportMapper;
import com.workbuddy.pbccrc.dao.ReportQueryLogMapper;
import com.workbuddy.pbccrc.model.dto.SubmitRequest;
import com.workbuddy.pbccrc.model.entity.Report;
import com.workbuddy.pbccrc.model.entity.ReportContent;
import com.workbuddy.pbccrc.model.enums.ReportStatus;
import com.workbuddy.pbccrc.service.SubmitService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 征信报告提交服务实现。
 *
 * <p>先存原文后异步解析，提交幂等按业务规则实现：7 天滚动窗口内同一
 * 「姓名 + 身份证」组合不允许重复提交，窗口过后同一客户可再次提交。
 * 幂等键取身份组合（与正文无关），以 Redis 带 7 天 TTL 的原子锁抢占（Redis 为必需
 * 依赖），并在事务内以报告主表 7 天窗口行锁查询做二次校验。报告正文 MD5 仅作内容
 * 完整性校验字段（{@code content_hash}），不参与幂等判定。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
@Slf4j
@Service
public class SubmitServiceImpl implements SubmitService {

    /** 提交幂等锁的键前缀，完整键为 {@code pbccrc:submit:lock:{md5(证件|姓名)}}。 */
    private static final String IDENTITY_LOCK_PREFIX = "pbccrc:submit:lock:";

    private final ReportMapper reportMapper;
    private final ReportContentMapper contentMapper;
    private final ReportQueryLogMapper queryLogMapper;
    private final PbccrcProperties properties;
    private final RedissonClient redissonClient;

    /**
     * 构造方法，由 Spring 注入依赖。
     *
     * @param reportMapper    报告主表数据访问对象
     * @param contentMapper   报告原文数据访问对象
     * @param queryLogMapper  客户查询流水数据访问对象
     * @param properties      业务配置
     * @param redissonClient  Redis 客户端，用于提交幂等锁（必需依赖）
     */
    public SubmitServiceImpl(ReportMapper reportMapper, ReportContentMapper contentMapper,
                             ReportQueryLogMapper queryLogMapper, PbccrcProperties properties,
                             RedissonClient redissonClient) {
        this.reportMapper = reportMapper;
        this.contentMapper = contentMapper;
        this.queryLogMapper = queryLogMapper;
        this.properties = properties;
        this.redissonClient = redissonClient;
    }

    /**
     * 提交一份征信报告。
     *
     * <p>核心流程：1）以「姓名 + 证件号」计算身份幂等键，以正文计算内容哈希；
     * 2）以 Redis 带 7 天 TTL 的原子锁抢占身份幂等锁（必需）；3）在事务内以行锁查询主表
     * 近 7 天是否已有同身份提交做二次校验；4）插入主表、原文与查询流水；5）任一环节重复
     * 或异常则回滚并释放锁。窗口（7 天）由 {@code pbccrc.redis.submit-lock-ttl-days} 统一控制。</p>
     *
     * <p>锁的释放必须覆盖两种失败：方法体内的异常（catch 后立即释放），以及<b>事务提交阶段</b>
     * 的失败（死锁、连接中断等，异常发生在方法返回之后，catch 兜不住）。后者若不处理，锁会
     * 白占 7 天而主表没有记录，客户被永久拒之门外，故额外注册事务回滚回调兜底。释放前用
     * {@code compareAndSet} 校验持有者令牌，避免 TTL 过期后误删同身份新请求的锁。</p>
     *
     * @param req 提交请求，含证件号、姓名、申请编号、HTML 原文及操作人
     * @return 新生成报告的主键 reportId
     * @throws BizException 当该客户 7 天内已提交过时抛出
     */
    @Override
    @Transactional
    public Integer submit(SubmitRequest req) {
        String customerName = req.getCustomerName();
        String customerIdCard = req.getCustomerIdCard();
        // 身份幂等键：只取姓名+证件号，与报告正文无关（7 天内不允许同身份重复提交）
        String identityKey = DigestUtil.md5Hex(customerIdCard + "|" + customerName);
        // 内容哈希仅用于完整性校验，不参与幂等
        String contentHash = DigestUtil.md5Hex(req.getHtmlContent());
        int windowDays = properties.getRedis().getSubmitLockTtlDays();
        // 操作人优先取鉴权上下文中的可信身份，取不到才回退到请求体（评审 2.5）
        String operator = OperatorContext.getOrDefault(req.getOperator());
        // 锁值用一次性令牌而非幂等键：同身份的两次请求令牌不同，释放时才能确认「删的是自己的锁」
        String lockToken = UUID.randomUUID().toString();
        // Redis 为必需依赖：用带 7 天 TTL 的原子 set 抢占身份幂等锁，trySet 失败即拒
        RBucket<String> bucket = redissonClient.getBucket(IDENTITY_LOCK_PREFIX + identityKey);
        if (!bucket.trySet(lockToken, windowDays, TimeUnit.DAYS)) {
            throw new BizException(ResultCode.DUPLICATE_SUBMIT);
        }
        releaseOnRollback(bucket, lockToken);
        try {
            // 二次校验：事务内行锁查询近 N 天同身份报告，命中即视为窗口内重复提交
            Integer recentId = reportMapper.findRecentForUpdate(customerName, customerIdCard, LocalDateTime.now().minusDays(windowDays));
            if (recentId != null) {
                throw new BizException(ResultCode.DUPLICATE_SUBMIT);
            }

            Report report = new Report();
            report.setCustomerIdCard(customerIdCard);
            report.setCustomerName(customerName);
            report.setAppNo(req.getAppNo());
            report.setStatus(ReportStatus.PENDING);
            report.setRetryCount(0);
            report.setHeartbeatTime(LocalDateTime.now());
            report.setContentHash(contentHash);
            report.setCreateTime(LocalDateTime.now());
            report.setCreatorId(operator);
            reportMapper.insert(report);

            ReportContent content = new ReportContent();
            content.setReportId(report.getId());
            content.setContent(req.getHtmlContent());
            content.setCreatorId(operator);
            content.setCreateTime(LocalDateTime.now());
            contentMapper.insert(content);

            // 提交流水：一次成功提交记一条，唯一键冲突仅忽略，不再作为幂等判定依据
            queryLogMapper.insertIgnore(customerName, customerIdCard, report.getId(), operator);
            log.info("report {} 提交成功, 待异步解析", report.getId());
            return report.getId();
        } catch (RuntimeException e) {
            // 方法体内的失败：立即释放，避免误拦截后续重试
            releaseLock(bucket, lockToken);
            throw e;
        }
    }

    /**
     * 注册事务回滚后的锁释放回调，兜住「方法体正常但提交阶段失败」的场景。
     *
     * <p>无事务上下文时（例如直接 new 出来做单测）不注册，由方法体 catch 负责释放。</p>
     *
     * @param bucket    幂等锁
     * @param lockToken 本次持有的锁令牌
     */
    private void releaseOnRollback(RBucket<String> bucket, String lockToken) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    releaseLock(bucket, lockToken);
                }
            }
        });
    }

    /**
     * 释放幂等锁：仅当锁值仍是本次令牌时才删除，避免删掉 TTL 过期后同身份新请求占住的锁。
     *
     * @param bucket    幂等锁
     * @param lockToken 本次持有的锁令牌
     */
    private void releaseLock(RBucket<String> bucket, String lockToken) {
        try {
            if (!bucket.compareAndSet(lockToken, null)) {
                log.debug("幂等锁持有者已变更，跳过释放");
            }
        } catch (RuntimeException e) {
            // 释放失败不影响主流程，最坏是锁留到 TTL 到期
            log.warn("释放提交幂等锁失败，将由 TTL 到期自动失效", e);
        }
    }
}
