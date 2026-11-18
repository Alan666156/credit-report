package com.workbuddy.pbccrc.service.impl;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 提交幂等的端到端护栏：直接驱动真实的 {@link SubmitServiceImpl#submit}，用一个带状态的
 * Redis 假实现模拟 Redisson {@code trySet(key, ttl)} 的原子成败语义，断言
 * 「7 天窗口内同身份（正文不同也）拒 / 不同身份放行 / 异常释放锁 / Redis 不可用即失败」。
 *
 * <p>为什么需要这一层：已有的幂等测试只单独验证 DB 的 {@code findRecentForUpdate}，或只验证
 * 幂等键推导（{@code trySet} 被一律 stub 成 true）。于是即便 submit() 完全忽略 trySet 的
 * 返回值、让重复请求长驱直入，那些测试仍会全绿——这正是原文警告的「测试从头绿到尾，恰恰
 * 可能没测到该修的地方」。本测试让 trySet 具有真实成败语义，把整条提交链路是否真的拒绝
 * 重复钉死。规则见项目根目录 REVIEW.md，断言不得为了跑绿而放宽。</p>
 *
 * @author Alan
 * @date 2026-11-18
 */
class SubmitServiceImplIdempotencyTest {

    /** key -> 到期纳秒，模拟 Redis 带 TTL 的原子占位。 */
    private final ConcurrentHashMap<String, Long> locks = new ConcurrentHashMap<>();
    /** key -> 持有者令牌，模拟 compareAndSet 的「只删自己那把锁」语义。 */
    private final ConcurrentHashMap<String, String> tokens = new ConcurrentHashMap<>();
    private RedissonClient client;
    private ReportMapper reportMapper;
    private SubmitServiceImpl service;

    @BeforeEach
    void setUp() {
        locks.clear();
        tokens.clear();
        client = mock(RedissonClient.class);
        doAnswer(inv -> {
            String key = inv.getArgument(0);
            RBucket<String> bucket = mock(RBucket.class);
            when(bucket.trySet(any(), anyLong(), any(TimeUnit.class))).thenAnswer(trySet -> {
                String token = trySet.getArgument(0);
                long ttl = trySet.getArgument(1);
                TimeUnit unit = trySet.getArgument(2);
                final boolean[] acquired = {false};
                locks.compute(key, (k, existing) -> {
                    long now = System.nanoTime();
                    if (existing != null && existing > now) {
                        return existing;              // 未过期：抢占失败
                    }
                    acquired[0] = true;
                    return now + unit.toNanos(ttl);   // 抢占成功，写入到期时间
                });
                if (acquired[0]) {
                    tokens.put(key, token);
                }
                return acquired[0];
            });
            // 释放走 compareAndSet(token, null)：值仍是本次令牌才删除，等价于 Redis 的原子比较删除
            when(bucket.compareAndSet(any(), isNull())).thenAnswer(cas -> {
                String expect = cas.getArgument(0);
                String holder = tokens.get(key);
                if (holder == null || !holder.equals(expect)) {
                    return false;
                }
                tokens.remove(key);
                locks.remove(key);
                return true;
            });
            return bucket;
        }).when(client).getBucket(anyString());

        reportMapper = mock(ReportMapper.class);
        when(reportMapper.findRecentForUpdate(anyString(), anyString(), any())).thenReturn(null);
        when(reportMapper.insert(any(Report.class))).thenAnswer(inv -> {
            Report r = inv.getArgument(0);
            r.setId(1);
            return 1;
        });
        ReportContentMapper contentMapper = mock(ReportContentMapper.class);
        when(contentMapper.insert(any(ReportContent.class))).thenReturn(1);
        ReportQueryLogMapper logMapper = mock(ReportQueryLogMapper.class);
        when(logMapper.insertIgnore(anyString(), anyString(), anyInt(), anyString())).thenReturn(1);

        service = new SubmitServiceImpl(reportMapper, contentMapper, logMapper,
                new PbccrcProperties(), client);
    }

    @AfterEach
    void tearDown() {
        OperatorContext.clear();
    }

    private static SubmitRequest req(String name, String id, String html) {
        SubmitRequest r = new SubmitRequest();
        r.setCustomerName(name);
        r.setCustomerIdCard(id);
        r.setHtmlContent(html);
        return r;
    }

    /** 核心不变量：7 天窗口内同一姓名+证件，即使正文完全不同也必须拒绝第二次提交。 */
    @Test
    void sameIdentityDifferentContentWithinWindowIsRejected() {
        assertNotNull(service.submit(req("张三", "110101199001011234", "<html>正文 A</html>")));
        BizException ex = assertThrows(BizException.class,
                () -> service.submit(req("张三", "110101199001011234", "<html>完全不同的正文 B</html>")));
        assertEquals(ResultCode.DUPLICATE_SUBMIT, ex.getResultCode());
        verify(reportMapper, times(1)).insert(any(Report.class)); // 重复提交不得再次落库
    }

    /** 去重维度是身份而非正文：不同姓名+证件即使用完全相同的正文也应放行。 */
    @Test
    void differentIdentityWithSameContentIsAllowed() {
        String sameHtml = "<html>同一份正文</html>";
        assertNotNull(service.submit(req("张三", "110101199001011234", sameHtml)));
        assertNotNull(service.submit(req("李四", "110101199001019999", sameHtml)));
        verify(reportMapper, times(2)).insert(any(Report.class));
        assertEquals(2, locks.size(), "不同身份应各自占用一把幂等锁");
    }

    /** 差分/二次校验：Redis 锁已过期但主表仍在窗口内时，DB 二次校验必须兜住并释放本次锁。 */
    @Test
    void dbSecondaryCheckRejectsWhenRedisLockAlreadyExpired() {
        when(reportMapper.findRecentForUpdate(anyString(), anyString(), any())).thenReturn(99);
        BizException ex = assertThrows(BizException.class,
                () -> service.submit(req("张三", "110101199001011234", "<html>A</html>")));
        assertEquals(ResultCode.DUPLICATE_SUBMIT, ex.getResultCode());
        verify(reportMapper, never()).insert(any(Report.class));
        assertTrue(locks.isEmpty(), "重复被拒时异常路径应释放本次抢占的锁");
    }

    /** 交给 DB 二次校验的窗口起点必须是「当前时间 - 7 天」。 */
    @Test
    void windowPassedToDbCheckIsSevenDays() {
        service.submit(req("张三", "110101199001011234", "<html>A</html>"));
        ArgumentCaptor<LocalDateTime> since = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(reportMapper).findRecentForUpdate(eq("张三"), eq("110101199001011234"), since.capture());
        assertEquals(7, ChronoUnit.DAYS.between(since.getValue(), LocalDateTime.now()),
                "二次校验窗口起点必须是当前时间 - 7 天");
    }

    /** Redis 为必需依赖：拿不到 Redis 就失败，不得退化成「仅靠 DB 校验」静默放行。 */
    @Test
    void redisUnavailableFailsFastWithoutFallback() {
        doThrow(new IllegalStateException("redis unavailable")).when(client).getBucket(anyString());
        assertThrows(RuntimeException.class,
                () -> service.submit(req("张三", "110101199001011234", "<html>A</html>")));
        verify(reportMapper, never()).insert(any(Report.class));
        verify(reportMapper, never()).findRecentForUpdate(anyString(), anyString(), any());
    }

    /**
     * 事务回滚必须释放幂等锁。
     *
     * <p>提交阶段的失败（死锁、连接中断）发生在 {@code submit()} 返回之后，方法体的 catch
     * 根本兜不住：锁会白占 7 天，而主表没有记录，客户被永久拒之门外。这里模拟 Spring 在回滚后
     * 回调 {@code afterCompletion}，断言锁被释放。</p>
     */
    @Test
    void releasesLockWhenTransactionRollsBack() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.submit(req("张三", "110101199001011234", "<html>A</html>"));
            assertEquals(1, locks.size(), "提交成功后锁应保留，用于挡住 7 天内的重复提交");

            completeTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);

            assertTrue(locks.isEmpty(), "回滚后必须释放幂等锁，否则客户被误锁 7 天且库里没有报告");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** 事务正常提交时锁必须保留，否则 7 天窗口形同虚设。 */
    @Test
    void keepsLockWhenTransactionCommits() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.submit(req("张三", "110101199001011234", "<html>A</html>"));
            completeTransaction(TransactionSynchronization.STATUS_COMMITTED);
            assertEquals(1, locks.size(), "提交成功后锁必须保留，覆盖 7 天窗口");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /**
     * 模拟 Spring 事务管理器在提交/回滚结束后回调已注册的同步器。
     *
     * @param status {@link TransactionSynchronization#STATUS_COMMITTED} 或
     *               {@link TransactionSynchronization#STATUS_ROLLED_BACK}
     */
    private void completeTransaction(int status) {
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCompletion(status);
        }
    }
}
