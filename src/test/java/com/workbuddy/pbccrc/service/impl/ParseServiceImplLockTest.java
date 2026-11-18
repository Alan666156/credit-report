package com.workbuddy.pbccrc.service.impl;

import com.workbuddy.pbccrc.config.PbccrcProperties;
import com.workbuddy.pbccrc.dao.ReportContentMapper;
import com.workbuddy.pbccrc.dao.ReportMapper;
import com.workbuddy.pbccrc.parser.ParsePipeline;
import com.workbuddy.pbccrc.service.AlertNotifier;
import com.workbuddy.pbccrc.service.ParseResultWriter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 解析互斥锁行为回归（评审 P1-3）：拿不到锁一律跳过，且锁租期必须活过超时判定。
 *
 * <p>两条断言各自对应一个真实故障模式：</p>
 * <ul>
 *   <li>拿不到锁还继续解析 → 同一份报告两个线程同时删改明细；</li>
 *   <li>租期短于「解析超时判定点」→ 看门狗判超时后锁先过期，补偿重投立刻拿到锁，
 *       与仍在跑的旧线程并发，等于没加锁。</li>
 * </ul>
 *
 * @author Alan
 * @date 2026-11-18
 */
class ParseServiceImplLockTest {

    private final ParseServiceImpl service;
    private final RLock lock;
    private final ReportMapper reportMapper;

    ParseServiceImplLockTest() {
        RedissonClient client = mock(RedissonClient.class);
        this.lock = mock(RLock.class);
        when(client.getLock(anyString())).thenReturn(lock);
        this.reportMapper = mock(ReportMapper.class);
        this.service = new ParseServiceImpl(reportMapper, mock(ReportContentMapper.class),
                mock(ParsePipeline.class), mock(ParseResultWriter.class),
                mock(AlertNotifier.class), new PbccrcProperties(), client);
    }

    /** 已有节点持有解析锁时不等待、不抢占，直接跳过（由补偿任务下一轮重投）。 */
    @Test
    void skipsParseWhenLockHeldByAnotherNode() throws Exception {
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        service.parseAsync(7);

        verify(reportMapper, never()).claim(anyInt());
        verify(lock, never()).unlock();
    }

    /** 抢锁必须是非阻塞的（waitTime=0），且租期 = 解析超时的两倍。 */
    @Test
    void acquireIsNonBlockingAndLeaseOutlivesParseTimeout() throws Exception {
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);
        int parseTimeoutMinutes = new PbccrcProperties().getParse().getParseTimeoutMinutes();

        service.parseAsync(7);

        ArgumentCaptor<Long> lease = ArgumentCaptor.forClass(Long.class);
        verify(lock).tryLock(eq(0L), lease.capture(), eq(TimeUnit.MINUTES));
        assertEquals(parseTimeoutMinutes * 2L, lease.getValue(),
                "租期必须是解析超时的两倍，否则超时判定后锁先失效，补偿重投会与旧线程并发");
    }
}
