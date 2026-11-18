package com.workbuddy.pbccrc.parser;

import com.workbuddy.pbccrc.dao.RuleMapper;
import com.workbuddy.pbccrc.engine.RuleCache;
import com.workbuddy.pbccrc.engine.RuleCacheHolder;
import com.workbuddy.pbccrc.engine.RuleFixtures;
import com.workbuddy.pbccrc.model.entity.CreditCardRecord;
import com.workbuddy.pbccrc.model.entity.LoanRecord;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** 用真实 HTML + 真实配置规则跑通整条解析流水线。 */
class ParsePipelineTest {

    private ParseContext parse() throws Exception {
        RuleMapper mapper = () -> RuleFixtures.load();
        RuleCache cache = new RuleCache(mapper);
        cache.reload();
        RuleCacheHolder holder = new RuleCacheHolder(cache);
        ParsePipeline pipeline = new ParsePipeline(
                new CreditDetailParser(holder),
                new PublicRecordParser(holder),
                new QueryRecordParser());

        String html = SampleReport.read(SampleReport.DEFAULT);
        ParseContext ctx = new ParseContext();
        ctx.setReportId(1);
        ctx.setHtml(html);
        ctx.setCustomerIdCard("test-id-card");
        ctx.setCustomerName("test-name");
        pipeline.parse(ctx);
        return ctx;
    }

    @Test
    void parsesHeadAndSummary() throws Exception {
        ParseContext ctx = parse();
        assertEquals("2021011600005505996742", ctx.getReport().getReportNo());
        assertEquals("杨凯俊", ctx.getReport().getName());
        assertEquals("身份证", ctx.getReport().getCertType());
        assertEquals(6, ctx.getReport().getCreditCardTotal());
        assertEquals(45, ctx.getReport().getOtherLoanTotal());
        assertEquals(16, ctx.getReport().getOtherLoanActive());
        assertEquals(2, ctx.getReport().getCreditCardOverdue());
    }

    @Test
    void parsesCreditDetails() throws Exception {
        ParseContext ctx = parse();
        assertFalse(ctx.getCreditCards().isEmpty());
        assertFalse(ctx.getLoans().isEmpty());
        CreditCardRecord card = ctx.getCreditCards().get(0);
        assertEquals(new BigDecimal("15000"), card.getCreditLimitMoney());
        assertEquals(new BigDecimal("4075"), card.getAlreadyUseMoney());
        assertEquals("华夏银行", card.getGrantOrganizations());
        LoanRecord loan = ctx.getLoans().get(0);
        assertEquals(new BigDecimal("16059"), loan.getGrantMoney());
        assertEquals("其他贷款", loan.getLoanType());
        assertEquals("0", loan.getType().toString());
    }

    /** 评审 2.3：每推进一个阶段都应回调进度钩子，供心跳按进度刷新。 */
    @Test
    void advancesProgressOncePerStage() throws Exception {
        RuleMapper mapper = () -> RuleFixtures.load();
        RuleCache cache = new RuleCache(mapper);
        cache.reload();
        ParsePipeline pipeline = new ParsePipeline(
                new CreditDetailParser(new RuleCacheHolder(cache)),
                new PublicRecordParser(new RuleCacheHolder(cache)),
                new QueryRecordParser());

        String html = SampleReport.read(SampleReport.DEFAULT);
        ParseContext ctx = new ParseContext();
        ctx.setReportId(1);
        ctx.setHtml(html);

        java.util.concurrent.atomic.AtomicInteger progress = new java.util.concurrent.atomic.AtomicInteger();
        pipeline.parse(ctx, progress::incrementAndGet);

        assertEquals(6, progress.get(), "六个阶段各推进一次进度回调");
    }

    @Test
    void parsesQueryRecordsAndDescriptions() throws Exception {
        ParseContext ctx = parse();
        assertEquals(36, ctx.getQueryRecords().size());
        assertEquals("1", ctx.getQueryRecords().get(0).getType());
        assertEquals("南京银行股份有限公司", ctx.getQueryRecords().get(0).getQueryOperator());
        assertNotNull(ctx.getReport().getDescription());
        assertFalse(ctx.getReport().getDescription().isBlank());
        assertNotNull(ctx.getReport().getCreditRecordDescription());
        assertNotNull(ctx.getReport().getQueryRecordDescription());
    }
}
