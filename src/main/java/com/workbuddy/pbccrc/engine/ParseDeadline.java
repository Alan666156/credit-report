package com.workbuddy.pbccrc.engine;

/**
 * 解析进度截止时间：记录最近一次「阶段推进」的时刻，供看门狗判断是否超时。
 *
 * <p>评审 2.3：旧实现由心跳线程每 10s 无条件刷新 heartbeat_time，导致解析真卡住时
 * 心跳照刷、永不超时。这里把「心跳」改成进度时间戳——只有在解析阶段真正推进时由
 * 调用方 {@link #markProgress()} 更新；看门狗用 {@link #isExpired(long)} 判断自上次
 * 推进以来是否已超过阈值，从而识别卡死而非仅识别进程死亡。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
public final class ParseDeadline {

    private volatile long lastProgressNanos = System.nanoTime();

    /**
     * 标记一次进度推进，重置超时计时。
     */
    public void markProgress() {
        lastProgressNanos = System.nanoTime();
    }

    /**
     * 判断自最近一次进度推进以来是否已超过指定时长。
     *
     * @param timeoutNanos 超时阈值（纳秒）
     * @return 超时返回 true
     */
    public boolean isExpired(long timeoutNanos) {
        return System.nanoTime() - lastProgressNanos >= timeoutNanos;
    }
}
