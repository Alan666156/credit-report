package com.workbuddy.pbccrc.service.impl;

import com.workbuddy.pbccrc.common.BizException;
import com.workbuddy.pbccrc.common.ResultCode;
import com.workbuddy.pbccrc.dao.DetailMapper;
import com.workbuddy.pbccrc.dao.ReportMapper;
import com.workbuddy.pbccrc.model.entity.*;
import com.workbuddy.pbccrc.parser.ParseContext;
import com.workbuddy.pbccrc.service.ParseResultWriter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 解析结果落库器实现。
 *
 * <p>在单一事务内完成入库：先按 reportId 删除所有类别的旧明细，再逐类插入新明细，
 * 最后更新主表汇总字段并将状态置为成功，保证重新解析时结果一致且不留脏数据。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
@Component
public class ParseResultWriterImpl implements ParseResultWriter {

    private static final String C_CARD = "t_pbccrc_credit_card_record";
    private static final String C_LOAN = "t_pbccrc_loan_record";
    private static final String C_QUERY = "t_pbccrc_query_record";
    private static final String C_ASSET = "t_pbccrc_asset_disposal";
    private static final String C_COMP = "t_pbccrc_compensate";
    private static final String C_GUAR = "t_pbccrc_guarantee";
    private static final String C_RAW = "t_pbccrc_credit_record";
    private static final String C_ADMIN = "t_pbccrc_admin_punishment";
    private static final String C_CIVIL = "t_pbccrc_ccivil_decision";
    private static final String C_COURT = "t_pbccrc_court_decision";
    private static final String C_TAX = "t_pbccrc_tax_arrears";
    private static final String C_TELECOM = "t_pbccrc_telecom_owe";

    /** 单批明细插入上限，避免几千条拼成一条 INSERT 撞 MySQL max_allowed_packet（评审 3.7）。 */
    private static final int BATCH_SIZE = 500;

    private final ReportMapper reportMapper;
    private final DetailMapper detailMapper;

    /**
     * 构造方法，由 Spring 注入依赖。
     *
     * @param reportMapper 报告主表数据访问对象
     * @param detailMapper 明细表通用数据访问对象
     */
    public ParseResultWriterImpl(ReportMapper reportMapper, DetailMapper detailMapper) {
        this.reportMapper = reportMapper;
        this.detailMapper = detailMapper;
    }

    /**
     * 将解析上下文中的结果写入数据库。
     *
     * <p>单事务内先删除全部旧明细，再按类别插入新明细，最后标记主表解析成功。</p>
     *
     * @param ctx 解析上下文，含报告主键与各类明细列表
     */
    @Override
    @Transactional
    public void write(ParseContext ctx) {
        Integer id = ctx.getReportId();
        // 先清理该报告的所有旧明细，避免重投时重复数据
        for (String table : new String[]{C_CARD, C_LOAN, C_QUERY, C_ASSET, C_COMP,
                C_GUAR, C_RAW, C_ADMIN, C_CIVIL, C_COURT, C_TAX, C_TELECOM}) {
            detailMapper.deleteByReportId(table, id);
        }
        insert(C_CARD, ctx.getCreditCards());
        insert(C_LOAN, ctx.getLoans());
        insert(C_QUERY, ctx.getQueryRecords());
        insert(C_ASSET, ctx.getAssetDisposals());
        insert(C_COMP, ctx.getCompensates());
        insert(C_GUAR, ctx.getGuarantees());
        insert(C_RAW, ctx.getRawCreditRecords());
        insert(C_ADMIN, ctx.getAdminPunishments());
        insert(C_CIVIL, ctx.getCivilDecisions());
        insert(C_COURT, ctx.getCourtDecisions());
        insert(C_TAX, ctx.getTaxArrears());
        insert(C_TELECOM, ctx.getTelecomOwes());

        // 评审 3.6：主键在此显式回填，不依赖报告头解析器的副作用；
        // markSuccess 返回值必须校验——影响 0 行说明主表已被改动（如超时判失败），
        // 此时抛异常回滚整个事务，避免明细挂在一份非 PARSING 的报告上。
        Report report = ctx.getReport();
        report.setId(id);
        if (reportMapper.markSuccess(report) == 0) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "报告状态回填失败，主表记录已变更");
        }
    }

    /**
     * 列表非空时按 {@link #BATCH_SIZE} 分批插入指定明细表。
     *
     * <p>评审 3.7：原实现把整份报告几千条明细拼成一条多行 INSERT，容易超过 MySQL
     * max_allowed_packet；分批后每批最多 500 条，既不撑爆包大小，又保留批量插入效率。</p>
     *
     * @param table 目标明细表名
     * @param list  待插入的明细实体列表，可为空
     */
    private void insert(String table, List<?> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        for (int from = 0; from < list.size(); from += BATCH_SIZE) {
            int to = Math.min(from + BATCH_SIZE, list.size());
            detailMapper.insertBatch(table, list.subList(from, to));
        }
    }
}
