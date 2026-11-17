package com.workbuddy.pbccrc.engine;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 进度截止时间测试（评审 2.3）：验证「只在阶段推进时重置超时计时」的语义。
 *
 * <p>修复前心跳定时无条件刷新，解析卡死仍被视为活动；这里断言只有 markProgress
 * 才会重置计时，停滞超过阈值即判超时，从而能被看门狗和补偿任务识别。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
class ParseDeadlineTest {

    /** 极短阈值下，未推进会被判超时；推进一次后重置计时，立即不再超时。 */
    @Test
    void progressResetsTimeout() throws Exception {
        long tiny = TimeUnit.MILLISECONDS.toNanos(30);
        ParseDeadline deadline = new ParseDeadline();

        Thread.sleep(60);
        assertTrue(deadline.isExpired(tiny), "停滞超过阈值应判超时");

        deadline.markProgress();
        assertFalse(deadline.isExpired(tiny), "阶段推进后应重置超时计时");

        Thread.sleep(60);
        assertTrue(deadline.isExpired(tiny), "再次停滞超过阈值应重新判超时");
    }
}
