package com.workbuddy.pbccrc.parser;

import com.workbuddy.pbccrc.dao.RuleMapper;
import com.workbuddy.pbccrc.engine.RuleCache;
import com.workbuddy.pbccrc.engine.RuleCacheHolder;
import com.workbuddy.pbccrc.engine.RuleFixtures;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** 用「所有信息都有」的完整真实报告验证标题容错、公共记录、担保与报告头修复。 */
class FullReportParseTest {

    private ParseContext parse() throws Exception {
        RuleMapper mapper = () -> RuleFixtures.load();
        RuleCache cache = new RuleCache(mapper);
        cache.reload();
        RuleCacheHolder holder = new RuleCacheHolder(cache);
        ParsePipeline pipeline = new ParsePipeline(
                new CreditDetailParser(holder),
                new PublicRecordParser(holder),
                new QueryRecordParser());

        String html;
        try (InputStream in = getClass().getResourceAsStream("/report_all.html")) {
            html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        ParseContext ctx = new ParseContext();
        ctx.setReportId(1);
        ctx.setHtml(html);
        pipeline.parse(ctx);
        return ctx;
    }

    /** 报告头：证件号码不再吞进「已婚」与区块引导文字，婚姻状况单独回填。 */
    @Test
    void parsesHeadWithoutSwallowingIntro() throws Exception {
        ParseContext ctx = parse();
        assertEquals("2018031200003557390896", ctx.getReport().getReportNo());
        assertEquals("征信三", ctx.getReport().getName());
        assertEquals("身份证", ctx.getReport().getCertType());
        assertEquals("**************2632", ctx.getReport().getCertNo());
        assertEquals("已婚", ctx.getReport().getMaritalStatus());
    }

    /** 信贷明细：h1 与 ol 之间夹 br 不再让整块因标题为 null 被丢弃。 */
    @Test
    void parsesCreditDetailsDespiteBrBetweenH1AndOl() throws Exception {
        ParseContext ctx = parse();
        assertEquals(12, ctx.getCreditCards().size(), "信用卡 12 条");
        assertEquals(5, ctx.getLoans().size(), "购房 1 + 其他贷款 4 条");
        assertEquals(17, ctx.getRawCreditRecords().size(), "原始信贷 12+5 条");
        assertFalse(ctx.getWarnings().stream().anyMatch(w -> w.contains("区块解析失败")),
                "不应再有整块解析失败的告警: " + ctx.getWarnings());
    }

    /** 为他人担保：3 条 li 不再被当成信贷块跳过，归入担保明细。 */
    @Test
    void parsesGuaranteesForOthers() throws Exception {
        ParseContext ctx = parse();
        assertEquals(3, ctx.getGuarantees().size(), "为他人担保 3 条");
        assertEquals("易平", ctx.getGuarantees().get(0).getGuaranteeName());
    }

    /** 公共记录：第一处真块未被「系统中没有」覆盖，电信欠费明细被解析出来。 */
    @Test
    void parsesTelecomOweFromRealPublicRecord() throws Exception {
        ParseContext ctx = parse();
        assertEquals(5, ctx.getTelecomOwes().size(), "电信欠费 5 条");
        assertTrue(ctx.getReport().getCommonRecordDescription().contains("电信欠费"));
        assertFalse(ctx.getReport().getCommonRecordDescription().contains("系统中没有"),
                "真块不应被重复造数据的「系统中没有」覆盖");
    }
}
