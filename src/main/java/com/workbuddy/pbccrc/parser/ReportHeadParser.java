package com.workbuddy.pbccrc.parser;

import com.workbuddy.pbccrc.engine.ValueConverter;
import com.workbuddy.pbccrc.model.entity.Report;

import java.time.LocalDateTime;

/**
 * 报告头解析器。
 *
 * <p>回填报告编号、查询时间、报告时间、姓名、证件类型、证件号码与婚姻状况；
 * 基础字段（主键、客户信息、内容哈希）直接取自解析上下文，其余从报告头文本按标签切分提取。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
public final class ReportHeadParser {

    private ReportHeadParser() {
    }

    /**
     * 解析报告头并回填主表。
     *
     * @param ctx   解析上下文
     * @param parts 报告分块结果
     */
    public static void parse(ParseContext ctx, BlockSplitter.Parts parts) {
        Report r = ctx.getReport();
        r.setId(ctx.getReportId());
        r.setCustomerIdCard(ctx.getCustomerIdCard());
        r.setCustomerName(ctx.getCustomerName());
        r.setAppNo(ctx.getAppNo());
        r.setContentHash(ctx.getContentHash());

        String head = parts.getHeadText();
        String[] segs = head.split("(?=(?:报告编号|查询时间|报告时间|姓名|证件类型|证件号码)：|(?:未婚|已婚|离异|丧偶)(?=\\s|$))");
        for (String raw : segs) {
            String seg = raw.trim();
            if (seg.startsWith("报告编号：")) {
                r.setReportNo(HtmlUtil.afterColon(seg, "报告编号："));
            } else if (seg.startsWith("查询时间：")) {
                r.setQueryTime((LocalDateTime) ValueConverter.convert("QUERY_TIME", HtmlUtil.afterColon(seg, "查询时间：")));
            } else if (seg.startsWith("报告时间：")) {
                r.setReportTime((LocalDateTime) ValueConverter.convert("REPORT_TIME", HtmlUtil.afterColon(seg, "报告时间：")));
            } else if (seg.startsWith("姓名：")) {
                r.setName(HtmlUtil.afterColon(seg, "姓名："));
            } else if (seg.startsWith("证件类型：")) {
                r.setCertType(HtmlUtil.afterColon(seg, "证件类型："));
            } else if (seg.startsWith("证件号码：")) {
                String v = HtmlUtil.afterColon(seg, "证件号码：");
                if (v != null) {
                    // 报告头常把婚姻状况紧跟在证件号后，截到婚姻状况之前
                    v = v.replaceAll("(?:未婚|已婚|离异|丧偶).*$", "").trim();
                }
                r.setCertNo(v == null || v.isEmpty() ? null : v);
            } else if (seg.matches("(未婚|已婚|离异|丧偶)")) {
                r.setMaritalStatus(seg);
            }
        }
    }
}
