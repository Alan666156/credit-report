package com.workbuddy.pbccrc.parser;

import org.jsoup.nodes.Element;

/**
 * 说明与各区块描述文本解析器。
 *
 * <p>将说明 ol 文本、信贷与查询区块的引导描述、信息概要表文本回填到主表对应字段。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
public final class DescriptionParser {

    private DescriptionParser() {
    }

    /**
     * 解析说明与各区块描述文本并回填主表。
     *
     * @param ctx   解析上下文
     * @param parts 报告分块结果
     */
    public static void parse(ParseContext ctx, BlockSplitter.Parts parts) {
        ctx.getReport().setDescription(parts.getDescriptionText());
        ctx.getReport().setCreditRecordDescription(parts.getCreditIntroText());
        ctx.getReport().setQueryRecordDescription(parts.getQueryIntroText());
        Element table = parts.getSummaryTable();
        ctx.getReport().setCreditRecordSummary(table == null ? null : HtmlUtil.clean(table.text()));
    }
}
