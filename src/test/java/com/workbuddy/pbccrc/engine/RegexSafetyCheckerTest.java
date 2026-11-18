package com.workbuddy.pbccrc.engine;

import com.workbuddy.pbccrc.common.BizException;
import com.workbuddy.pbccrc.model.dto.RuleRow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 正则回溯风险校验的判定边界。
 *
 * <p>两个方向都要钉住：危险的必须拦住，合法的不能误伤。误伤的代价是服务起不来，
 * 漏放的代价是解析线程被钉死，所以每个形态都单独断言。</p>
 *
 * @author Alan
 * @date 2026-11-18
 */
class RegexSafetyCheckerTest {

    /**
     * 断言给定正则存在指定编码的危险项。
     *
     * @param regex 正则
     * @param code  期望的检查项编码
     */
    private static void assertDangerous(String regex, String code) {
        List<RegexSafetyChecker.Finding> findings = RegexSafetyChecker.inspect(regex);
        assertTrue(RegexSafetyChecker.hasDangerous(findings), "应判为危险: " + regex);
        assertTrue(findings.stream().anyMatch(f -> f.getCode().equals(code)
                        && f.getRisk() == RegexSafetyChecker.Risk.DANGEROUS),
                "应命中 " + code + "，实际=" + findings.stream().map(RegexSafetyChecker.Finding::getCode).toList());
    }

    /**
     * 断言给定正则没有任何发现。
     *
     * @param regex 正则
     */
    private static void assertClean(String regex) {
        List<RegexSafetyChecker.Finding> findings = RegexSafetyChecker.inspect(regex);
        assertTrue(findings.isEmpty(),
                "不应有发现: " + regex + "，实际=" + findings.stream().map(RegexSafetyChecker.Finding::getCode).toList());
    }

    /** 嵌套无界重复：组内已有无界量词，外层再重复。 */
    @Test
    void detectsNestedRepeat() {
        assertDangerous("(a+)+", "NESTED_REPEAT");
        assertDangerous("(\\d*)*", "NESTED_REPEAT");
        assertDangerous("(.*)*", "NESTED_REPEAT");
        assertDangerous("((ab)+)*", "NESTED_REPEAT");
        assertDangerous("(a+){2,}", "NESTED_REPEAT");
    }

    /**
     * 有界重复的阻断线在 3 次：实测 {@code (.*a){4}} 已产生 64 万次字符读取，
     * {@code (.*a){8}} 接近 7900 万次，因此不能等到 10 次才拦。
     */
    @Test
    void boundedNestedRepeatIsCaughtFromThreeRepeats() {
        assertDangerous("(.*a){3}b", "NESTED_REPEAT");
        assertDangerous("(.*a){8}b", "NESTED_REPEAT");
        assertClean("(.*a){2}b");
    }

    /** 可空重复：组自身能匹配空串，重复时每轮都不消耗字符。 */
    @Test
    void detectsEmptyRepeat() {
        assertDangerous("(a?)+", "EMPTY_REPEAT");
        assertDangerous("(a|)*", "EMPTY_REPEAT");
        assertDangerous("(a{0,3})+", "EMPTY_REPEAT");
        assertDangerous("(?:x?)+", "EMPTY_REPEAT");
    }

    /** 多处通配只告警，不阻断加载。 */
    @Test
    void multiWildcardIsSuspiciousOnly() {
        List<RegexSafetyChecker.Finding> findings = RegexSafetyChecker.inspect("发放的.*（(.*)）");
        assertFalse(RegexSafetyChecker.hasDangerous(findings), "多处 .* 不应阻断加载");
        assertTrue(findings.stream().anyMatch(f -> f.getCode().equals("MULTI_WILDCARD")));
        assertEquals(1, findings.size(), "同一规则的多处通配合并成一条结论");
    }

    /** 现有规则的常见写法不能被误伤：懒惰量词、前瞻后顾、线性重复。 */
    @Test
    void doesNotFlagLegalPatterns() {
        assertClean("(.*?)");
        assertClean("截至(.*?)(?<=月)");
        assertClean("信用额度(.*?)(?=[，。])");
        assertClean("(已结清|已转出)");
        assertClean("(^\\d+年\\d+月\\d+日)");
        assertClean("（(.*?)(?=账户）)");
        assertClean("(担保贷款合同金额|担保信用卡授信额度)(.*?)(?=[，。])");
        assertClean("\\d{4}年\\d{1,2}月\\d{1,2}日");
    }

    /** 字符类与转义里的元字符是字面量，不能当成量词或分组。 */
    @Test
    void ignoresMetaCharactersInsideCharClassAndEscape() {
        assertClean("[+*?()]+");
        assertClean("\\(a\\+\\)\\+");
        assertClean("\\Q(a+)+\\E");
        assertClean("[^()|*+]+");
    }

    /** 零宽断言里的危险分组同样要发现，断言不消费字符不代表不会有回溯。 */
    @Test
    void detectsDangerInsideLookaround() {
        assertDangerous("(?=(a+)+).*", "NESTED_REPEAT");
        assertDangerous("(?<=\\d)(a?)+", "EMPTY_REPEAT");
    }

    /** 空输入与 null 不抛异常。 */
    @Test
    void handlesEmptyInput() {
        assertTrue(RegexSafetyChecker.inspect(null).isEmpty());
        assertTrue(RegexSafetyChecker.inspect("").isEmpty());
    }

    /** 现有 55 条规则必须全部通过：任何一条被误判，服务就起不来。 */
    @Test
    void allExistingRulesPassTheCheck() {
        List<RuleRow> rows = RuleFixtures.load();
        assertFalse(rows.isEmpty(), "规则夹具不应为空");
        for (RuleRow row : rows) {
            List<RegexSafetyChecker.Finding> findings = RegexSafetyChecker.inspect(row.getValue());
            assertFalse(RegexSafetyChecker.hasDangerous(findings),
                    "现有规则被误判为危险: " + row.getName() + " = " + row.getValue()
                            + "，实际=" + findings.stream().map(RegexSafetyChecker.Finding::getCode).toList());
        }
    }

    /** 危险规则在加载期直接拒绝，且异常信息能定位到字段与正则。 */
    @Test
    void ruleCacheRejectsDangerousRegex() {
        RuleRow row = new RuleRow();
        row.setType("1");
        row.setName("BAD_FIELD");
        row.setValue("(a+)+");
        row.setValueIndex("1:1");

        BizException e = assertThrows(BizException.class, () -> RuleCache.build(List.of(row)));
        assertTrue(e.getMessage().contains("BAD_FIELD"), "异常信息应含字段名");
        assertTrue(e.getMessage().contains("NESTED_REPEAT"), "异常信息应含检查项编码");
    }

    /** 现有规则整体加载仍应成功（校验不改变既有行为）。 */
    @Test
    void ruleCacheStillLoadsExistingRules() {
        assertDoesNotThrow(() -> RuleCache.build(RuleFixtures.load()));
    }
}
