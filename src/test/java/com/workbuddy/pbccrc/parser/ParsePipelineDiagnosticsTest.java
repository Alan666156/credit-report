package com.workbuddy.pbccrc.parser;

import com.workbuddy.pbccrc.dao.RuleMapper;
import com.workbuddy.pbccrc.engine.RuleCache;
import com.workbuddy.pbccrc.engine.RuleCacheHolder;
import com.workbuddy.pbccrc.engine.RuleFixtures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 解析诊断信息与 {@code error_msg} 的边界：告警进日志，不进主表。
 *
 * <p>{@code error_msg} 只回答「这份报告为什么失败」。解析降级——某块标题没认出来、
 * 某条明细套规则失败——属于部分成功，报告本身仍然是可用的，写进 {@code error_msg}
 * 会让后台展示凭空多出一条错误，也会让按该字段配置的监控误报。</p>
 *
 * <p>空报告尤其要守住：没有概要表、没有明细都是合法输入，不该留下任何错误痕迹。</p>
 *
 * @author Alan
 * @date 2026-11-18
 */
class ParsePipelineDiagnosticsTest {

    /**
     * 结构齐全但完全没有数据：无概要表、无明细，既不告警也不写 error_msg。
     */
    @Test
    void emptyReportLeavesNoErrorMsg() {
        ParseContext ctx = parse("""
                <html><body>
                  <span class="h1">信贷记录</span>
                  <span class="h1">公共记录</span>
                  <span class="h1">查询记录</span>
                </body></html>
                """);
        assertTrue(ctx.getWarnings().isEmpty(),
                "空报告没有概要表是正常情况，不应产生告警: " + ctx.getWarnings());
        assertNull(ctx.getReport().getErrorMsg(),
                "空报告解析成功后 error_msg 必须为空，否则后台会显示成错误");
    }

    /**
     * 存在降级（信贷块标题未识别）时，告警仍被采集供排查，但不污染 error_msg。
     */
    @Test
    void degradedBlockIsLoggedButNotWrittenToErrorMsg() {
        ParseContext ctx = parse("""
                <html><body>
                  <span class="h1">信贷记录</span>
                  <span class="h1">某种未知记录</span>
                  <ol class="olstyle"><li>2020年1月1日，某银行发放的贷款</li></ol>
                  <span class="h1">公共记录</span>
                  <span class="h1">查询记录</span>
                </body></html>
                """);
        assertFalse(ctx.getWarnings().isEmpty(), "未识别标题应留下告警供排查");
        assertNotNull(ctx.getWarnings().stream().filter(w -> w.contains("标题未识别")).findFirst().orElse(null),
                "告警内容应指明是标题未识别: " + ctx.getWarnings());
        assertNull(ctx.getReport().getErrorMsg(),
                "降级属于部分成功，error_msg 只承载失败原因");
    }

    private static ParseContext parse(String html) {
        RuleMapper mapper = () -> RuleFixtures.load();
        RuleCache cache = new RuleCache(mapper);
        cache.reload();
        ParsePipeline pipeline = new ParsePipeline(
                new CreditDetailParser(new RuleCacheHolder(cache)),
                new PublicRecordParser(new RuleCacheHolder(cache)),
                new QueryRecordParser());
        ParseContext ctx = new ParseContext();
        ctx.setReportId(1);
        ctx.setHtml(html);
        pipeline.parse(ctx);
        return ctx;
    }
}
