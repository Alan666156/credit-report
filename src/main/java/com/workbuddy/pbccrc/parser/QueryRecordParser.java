package com.workbuddy.pbccrc.parser;

import com.workbuddy.pbccrc.model.entity.QueryRecord;
import org.springframework.stereotype.Component;

/**
 * 查询记录解析器。
 *
 * <p>解析机构查询与个人查询两张固定表头表格，前四列直接取为查询号、查询日期、
 * 操作员与查询原因；操作员字段按斜杠拆出前半段作为实际查询操作员。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
@Component
public class QueryRecordParser {

    /**
     * 解析全部查询记录块。
     *
     * @param ctx   解析上下文
     * @param parts 报告分块结果
     */
    public void parse(ParseContext ctx, BlockSplitter.Parts parts) {
        for (BlockSplitter.QueryBlock block : parts.getQueryBlocks()) {
            for (String[] row : block.getRows()) {
                QueryRecord r = new QueryRecord();
                r.setReportId(ctx.getReportId());
                r.setType(block.getType());
                r.setQueryNo(row[0]);
                r.setQueryDate(row[1]);
                r.setOperator(row[2]);
                r.setQueryReason(row[3]);
                // 操作员列含机构与姓名，以斜杠分隔，取前半段为实际查询操作员
                int slash = row[2].indexOf('/');
                r.setQueryOperator(HtmlUtil.clean(slash > 0 ? row[2].substring(0, slash) : row[2]));
                ctx.getQueryRecords().add(r);
            }
        }
    }
}
