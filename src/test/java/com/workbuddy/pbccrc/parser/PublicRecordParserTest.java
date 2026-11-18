package com.workbuddy.pbccrc.parser;

import com.workbuddy.pbccrc.dao.RuleMapper;
import com.workbuddy.pbccrc.engine.RuleCache;
import com.workbuddy.pbccrc.engine.RuleCacheHolder;
import com.workbuddy.pbccrc.engine.RuleFixtures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 公共记录类别判定的容错边界。
 *
 * <p>类别判定是关键词 if/else 链，未命中任何关键词的段落此前被静默丢弃。现要求：只记
 * 告警日志，不抛异常、不写入 {@code error_msg}、不强行归入某个明细表，原文仍完整保留在
 * {@code common_record_detail}，以便后续按样本补规则。</p>
 *
 * @author Alan
 * @date 2026-11-18
 */
class PublicRecordParserTest {

    /**
     * 构造带真实规则的解析器。
     *
     * @return 公共记录解析器
     */
    private static PublicRecordParser parser() {
        RuleMapper mapper = () -> RuleFixtures.load();
        RuleCache cache = new RuleCache(mapper);
        cache.reload();
        return new PublicRecordParser(new RuleCacheHolder(cache));
    }

    /**
     * 构造解析上下文。
     *
     * @return 上下文
     */
    private static ParseContext context() {
        ParseContext ctx = new ParseContext();
        ctx.setReportId(9);
        return ctx;
    }

    /** 未命中任何类别的段落：各明细表为空，原文留痕，且不污染 error_msg。 */
    @Test
    void unmatchedSegmentIsSkippedWithOriginalTextKept() {
        String text = "公共记录 这部分包含您最近5年内的公共信息。"
                + "2024年1月5日，某未知机构报送了一条无法归类的新记录。";
        BlockSplitter.Parts parts = new BlockSplitter.Parts();
        parts.setPublicText(text);

        ParseContext ctx = context();
        parser().parse(ctx, parts);

        assertEquals(0, ctx.getAssetDisposals().size());
        assertEquals(0, ctx.getCompensates().size());
        assertEquals(0, ctx.getGuarantees().size());
        assertEquals(0, ctx.getTelecomOwes().size());
        assertEquals(0, ctx.getAdminPunishments().size());
        assertEquals(text, ctx.getReport().getCommonRecordDetail(), "原文必须完整保留");
        assertTrue(ctx.getWarnings().isEmpty(), "未识别类别只记日志，不进 error_msg");
    }

    /** 未识别段落不影响同批次已识别类别的归类，计数只按命中项统计。 */
    @Test
    void unmatchedSegmentDoesNotDisturbMatchedOnes() {
        String text = "电信运营商：中国电信，欠费金额100元。"
                + "2024年1月5日，某未知机构报送了一条无法归类的新记录。";
        BlockSplitter.Parts parts = new BlockSplitter.Parts();
        parts.setPublicText(text);

        ParseContext ctx = context();
        parser().parse(ctx, parts);

        assertEquals(1, ctx.getTelecomOwes().size(), "电信欠费照常识别");
        assertEquals(0, ctx.getReport().getAssetTotal(), "未识别段落不计入资产处置条数");
        assertEquals(0, ctx.getReport().getRepaymentTotal());
        assertTrue(ctx.getWarnings().isEmpty());
    }
}
