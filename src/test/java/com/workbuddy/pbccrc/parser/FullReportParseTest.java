package com.workbuddy.pbccrc.parser;

import com.workbuddy.pbccrc.dao.RuleMapper;
import com.workbuddy.pbccrc.engine.RuleCache;
import com.workbuddy.pbccrc.engine.RuleCacheHolder;
import com.workbuddy.pbccrc.engine.RuleFixtures;
import com.workbuddy.pbccrc.model.entity.AssetDisposal;
import com.workbuddy.pbccrc.model.entity.Compensate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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

        String html = SampleReport.read(SampleReport.FULL);
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

    /**
     * 公共记录块只能取「公共记录」区块自身。
     *
     * <p>真实报告整份正文被最外层 table 包着，早期实现取「文档顺序第一个含公共记录且不含
     * 系统中没有的 table」，只要报告真存在公共记录就会把整份报告当成公共记录，进而让信贷
     * 明细被二次分类成公共记录（担保曾因此从 3 条变成 9 条）。</p>
     */
    @Test
    void publicRecordTextExcludesCreditSection() throws Exception {
        ParseContext ctx = parse();
        String desc = ctx.getReport().getCommonRecordDescription();
        assertNotNull(desc);
        assertTrue(desc.startsWith("公共记录"), "公共记录文本应从区块标题开始, 实际=" + desc);
        assertFalse(desc.contains("信用卡"), "公共记录文本不得混入信贷区内容, 实际=" + desc);
    }

    /** 资产处置与保证人代偿两块此前被信贷块标题白名单漏掉，整块明细进不了库。 */
    @Test
    void parsesAssetDisposalAndCompensateBlocks() throws Exception {
        ParseContext ctx = parse();
        assertEquals(1, ctx.getAssetDisposals().size(), "资产处置 1 条");
        assertEquals(1, ctx.getCompensates().size(), "保证人代偿 1 条");

        AssetDisposal asset = ctx.getAssetDisposals().get(0);
        assertEquals(new BigDecimal("8000002"), asset.getAmount(), "资产处置债权金额");
        assertEquals(new BigDecimal("80002"), asset.getBalance(), "资产处置余额");
        assertEquals(LocalDateTime.of(2018, 1, 10, 0, 0), asset.getDisposeDate());

        Compensate compensate = ctx.getCompensates().get(0);
        assertEquals(new BigDecimal("2263"), compensate.getAmount(), "累计代偿金额");
        assertEquals(LocalDateTime.of(2016, 6, 20, 0, 0), compensate.getDisposeDate());

        assertEquals(1, ctx.getReport().getAssetTotal(), "主表资产处置条数应回填");
        assertEquals(1, ctx.getReport().getRepaymentTotal(), "主表代偿条数应回填");
    }

    /** 各类明细都必须保留原文，便于规则变化后回溯与对账。 */
    @Test
    void keepsRawContentForAllDetailKinds() throws Exception {
        ParseContext ctx = parse();
        assertTrue(ctx.getAssetDisposals().stream().allMatch(e -> e.getContent() != null),
                "资产处置必须保留原文");
        assertTrue(ctx.getCompensates().stream().allMatch(e -> e.getContent() != null),
                "代偿必须保留原文");
        assertTrue(ctx.getGuarantees().stream().allMatch(e -> e.getContent() != null),
                "担保必须保留原文");
        assertFalse(ctx.getWarnings().stream().anyMatch(w -> w.contains("标题未识别")),
                "不应再有信贷块被标题白名单丢弃: " + ctx.getWarnings());
    }
}
