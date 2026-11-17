package com.workbuddy.pbccrc.service;

import com.workbuddy.pbccrc.parser.ParseContext;

/**
 * 解析结果落库器。
 *
 * <p>在单一事务内完成入库：先按 reportId 删除所有类别的旧明细，再逐类插入新明细，
 * 最后更新主表汇总字段并将状态置为成功，保证重新解析时结果一致且不留脏数据。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
public interface ParseResultWriter {

    /**
     * 将解析上下文中的结果写入数据库。
     *
     * <p>单事务内先删除全部旧明细，再按类别插入新明细，最后标记主表解析成功。</p>
     *
     * @param ctx 解析上下文，含报告主键与各类明细列表
     */
    void write(ParseContext ctx);
}
