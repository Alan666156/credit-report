package com.workbuddy.pbccrc.engine;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 正则引擎：全量编译 + 真实样本抽取 + value_index + 已修缺陷边界。 */
class RegexEngineTest {

    @Test
    void allRulesCompile() {
        assertEquals(55, RuleFixtures.load().size());
        assertDoesNotThrow(() -> RuleCache.build(RuleFixtures.load()));
    }

    @Test
    void creditCardExtractionFromRealLi() {
        String li = "2016年6月12日华夏银行发放的贷记卡（人民币账户）。截至2018年4月，信用额度15,000，已使用额度4,075。"
                + "最近5年内有1个月处于逾期状态，没有发生过90天以上逾期。";
        List<FieldRule> rules = RuleCache.build(RuleFixtures.load()).get("1");
        Map<String, Object> v = RegexEngine.extract(li, rules);

        assertEquals(LocalDateTime.of(2016, 6, 12, 0, 0), v.get("GRANT_MONEY_DATE"));
        assertEquals("华夏银行", v.get("GRANT_ORGANIZATIONS"));
        assertEquals(new BigDecimal("15000"), v.get("CREDIT_LIMIT_MONEY"));
        assertEquals(new BigDecimal("4075"), v.get("ALREADY_USE_MONEY"));
        assertEquals(1, v.get("OVERDUE_MONTH"));
        assertEquals("贷记卡", v.get("CREDIT_TYPE"));
    }

    @Test
    void valueIndexGroupTwo() {
        FieldRule rule = new FieldRule("5", "CONTRACT_AMOUNT",
                "(担保贷款合同金额|担保信用卡授信额度)(.*?)(?=[，。])", "2:1", ValueType.MONEY);
        Map<String, Object> v = RegexEngine.extract("担保贷款合同金额100万元，担保金额80万元。", List.of(rule));
        assertEquals(new BigDecimal("100"), v.get("CONTRACT_AMOUNT"));
    }

    @Test
    void fixedId22Boundary() {
        FieldRule fixed = new FieldRule("2", "SETTLEMENT_DATE", "，(.*?)(?=已结清|已转出)", "1:1", ValueType.STRING);
        FieldRule broken = new FieldRule("2", "SETTLEMENT_DATE", "，(.*?)(?=[已结清|已转出])", "1:1", ValueType.STRING);
        String text = "，结清后余额，已转出";
        assertEquals("结清后余额，", RegexEngine.extract(text, List.of(fixed)).get("SETTLEMENT_DATE"));
        assertNull(RegexEngine.extract(text, List.of(broken)).get("SETTLEMENT_DATE"));
    }

    @Test
    void fixedDateAnchor() {
        FieldRule fixed = new FieldRule("1", "GRANT_MONEY_DATE", "(^\\d+年\\d+月\\d+日)", "1:1", ValueType.DATE);
        assertEquals(LocalDateTime.of(2016, 6, 12, 0, 0),
                RegexEngine.extract("2016年6月12日华夏银行发放的贷记卡", List.of(fixed)).get("GRANT_MONEY_DATE"));
    }
}
