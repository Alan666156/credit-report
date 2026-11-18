package com.workbuddy.pbccrc.parser;

import com.workbuddy.pbccrc.model.entity.Report;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.Element;

import java.util.List;

/**
 * 信息概要解析器。
 *
 * <p>解析「账户数」等 5×3 计数表，按行标签将信用卡、购房贷款、其他贷款三类
 * 的账户总数、未结清数、逾期数、90天以上逾期数、担保笔数回填到主表 15 个汇总字段。</p>
 *
 * <p>报告可能完全没有账户数据（合法的空报告），此时页面上不会出现信息概要表。这属于
 * 正常情况而非解析异常，因此只记 debug 日志、不产生告警，避免空报告被误标成有问题的报告。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
@Slf4j
public final class CreditSummaryParser {

    private CreditSummaryParser() {
    }

    /**
     * 解析信息概要表并回填主表汇总字段。
     *
     * @param ctx   解析上下文
     * @param parts 报告分块结果
     */
    public static void parse(ParseContext ctx, BlockSplitter.Parts parts) {
        Element table = parts.getSummaryTable();
        if (table == null) {
            // 空报告本来就没有概要表，是合法输入，不能当成告警往失败方向靠
            log.debug("report {} 无信息概要表，跳过汇总回填", ctx.getReportId());
            return;
        }
        Report r = ctx.getReport();
        for (Element tr : table.select("tr")) {
            List<String> cells = tr.select("td").stream().map(e -> HtmlUtil.clean(e.text())).toList();
            if (cells.size() < 4) {
                continue;
            }
            Integer cc = intOf(cells.get(1));
            Integer house = intOf(cells.get(2));
            Integer other = intOf(cells.get(3));
            if (cc == null || house == null || other == null) {
                continue;
            }
            String label = cells.get(0).replace(" ", "");
            switch (label) {
                case "账户数" -> { r.setCreditCardTotal(cc); r.setHousingLoanTotal(house); r.setOtherLoanTotal(other); }
                case "未结清/未销户账户数" -> { r.setCreditCardActive(cc); r.setHousingLoanActive(house); r.setOtherLoanActive(other); }
                case "发生过逾期的账户数" -> { r.setCreditCardOverdue(cc); r.setHousingLoanOverdue(house); r.setOtherLoanOverdue(other); }
                case "发生过90天以上逾期的账户数" -> { r.setCreditCardSeriousOverdue(cc); r.setHousingLoanSeriousOverdue(house); r.setOtherLoanSeriousOverdue(other); }
                case "为他人担保笔数" -> { r.setCreditCardGuarantee(cc); r.setHousingLoanGuarantee(house); r.setOtherLoanGuarantee(other); }
                default -> { /* 忽略未知行 */ }
            }
        }
    }

    /**
     * 将单元格文本解析为整数，去除千分位逗号。
     *
     * @param s 单元格文本
     * @return 整数，解析失败返回 null
     */
    private static Integer intOf(String s) {
        try {
            return Integer.valueOf(s.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
