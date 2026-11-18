package com.workbuddy.pbccrc.engine;

import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将正则抓取到的字符串转换为入库目标类型。
 *
 * <p>支持金额、日期、标志位、整数与字符串五类转换。单字段转换失败返回 {@code null}，
 * 不影响整份报告其余字段的解析；字段名约定见 {@link ValueType#of(String)}。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
@Slf4j
public final class ValueConverter {

    private static final Pattern YMD = Pattern.compile("(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日");
    private static final Pattern YM = Pattern.compile("(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月");
    private static final Pattern DOT_DT = Pattern.compile("(\\d{4})[./-](\\d{1,2})[./-](\\d{1,2})(?:\\s+(\\d{1,2}):(\\d{2}):(\\d{2}))?");
    private static final Pattern DIGITS = Pattern.compile("-?\\d+");

    private ValueConverter() {
    }

    /**
     * 标志位字段名到「命中值」的映射。
     *
     * <p>这些列在库里的注释是「负面状态」语义：{@code 0 是 / 1 否}，而规则匹配的是负面关键词
     * （已变成呆账 / 已结清 / 已销户），命中即代表该状态成立，故回填 {@code 0}。
     * {@code ACTIVE} 是例外：它匹配的是「未激活」，而列语义是 {@code 1=未激活}，故回填 {@code 1}。</p>
     *
     * <p>早期实现用 if/else 硬编码并兜底返回 {@code 1}，新增 FLAG 字段时不会报错、只会静默拿到
     * {@code 1}；改为显式映射后，未登记字段会留下告警日志。</p>
     */
    private static final Map<String, String> FLAG_MATCHED_VALUE = Map.of(
            "BAD", "0",
            "SETTLEMENT", "0",
            "CLOSE_ACCOUNT", "0",
            "ACTIVE", "1");

    /** 未登记的标志位字段的兜底值，与库中列默认值保持一致。 */
    private static final String FLAG_DEFAULT = "1";

    /**
     * 按字段名推断类型后转换原始值。
     *
     * @param fieldName 目标字段名
     * @param raw       正则抓取的原始字符串
     * @return 转换后的值，转换失败或空值返回 null
     */
    public static Object convert(String fieldName, String raw) {
        return convert(fieldName, raw, ValueType.of(fieldName));
    }

    /**
     * 按指定类型转换原始值。
     *
     * @param fieldName 目标字段名
     * @param raw       正则抓取的原始字符串
     * @param type      目标值类型
     * @return 转换后的值，空值（含「--」）或转换失败返回 null
     */
    public static Object convert(String fieldName, String raw, ValueType type) {
        if (raw == null || raw.isBlank() || "--".equals(raw)) {
            return null;
        }
        try {
            return switch (type) {
                case MONEY -> money(raw);
                case DATE -> date(raw);
                case FLAG -> flag(fieldName);
                case INT -> integer(raw);
                case STRING -> raw;
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 解析金额：去除千分位与「元」后取首个数字串。
     *
     * @param raw 原始金额字符串
     * @return 金额，无数字返回 null
     */
    private static BigDecimal money(String raw) {
        String s = raw.replace(",", "").replace("，", "").replace("元", "").trim();
        Matcher m = DIGITS.matcher(s);
        if (!m.find()) {
            return null;
        }
        return new BigDecimal(m.group());
    }

    /**
     * 解析日期：依次尝试「年月日」「年月」「点/斜杠/横杠分隔」三种格式。
     *
     * @param raw 原始日期字符串
     * @return 日期时间，均不匹配返回 null
     */
    private static LocalDateTime date(String raw) {
        Matcher m = YMD.matcher(raw);
        if (m.find()) {
            return LocalDateTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)), 0, 0);
        }
        m = YM.matcher(raw);
        if (m.find()) {
            return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), 1)
                    .atStartOfDay();
        }
        m = DOT_DT.matcher(raw);
        if (m.find()) {
            int year = Integer.parseInt(m.group(1));
            int month = Integer.parseInt(m.group(2));
            int day = Integer.parseInt(m.group(3));
            int hour = m.group(4) == null ? 0 : Integer.parseInt(m.group(4));
            int min = m.group(5) == null ? 0 : Integer.parseInt(m.group(5));
            int sec = m.group(6) == null ? 0 : Integer.parseInt(m.group(6));
            return LocalDateTime.of(year, month, day, hour, min, sec);
        }
        return null;
    }

    /**
     * 解析标志位：命中即代表该状态成立，按 {@link #FLAG_MATCHED_VALUE} 回填「0」或「1」。
     *
     * @param fieldName 目标字段名
     * @return 标志位字符串
     */
    private static String flag(String fieldName) {
        String matched = FLAG_MATCHED_VALUE.get(fieldName.toUpperCase());
        if (matched == null) {
            log.warn("未登记的标志位字段 {}，按默认值 {} 处理，请检查命名约定", fieldName, FLAG_DEFAULT);
            return FLAG_DEFAULT;
        }
        return matched;
    }

    /**
     * 解析整数：取首个整数串。
     *
     * @param raw 原始字符串
     * @return 整数，无数字返回 null
     */
    private static Integer integer(String raw) {
        Matcher m = DIGITS.matcher(raw);
        return m.find() ? Integer.valueOf(m.group()) : null;
    }
}
