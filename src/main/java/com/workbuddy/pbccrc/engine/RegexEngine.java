package com.workbuddy.pbccrc.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * 按配置规则从单段自然语言（一个 li 元素）中抽取字段。
 *
 * <p>规则中的 {@code valueIndex} 语义为「第几组:第几次匹配」：先按正则依次匹配，
 * 取第 N 次匹配结果的第 M 组。抽取到的原始字符串经 {@link ValueConverter} 转换后回填，
 * 未命中或转换失败的字段不会出现在结果中。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
public final class RegexEngine {

    private RegexEngine() {
    }

    /**
     * 按规则从文本中抽取字段。
     *
     * @param text  待抽取的自然语言文本
     * @param rules 抽取规则列表
     * @return 字段名到转换后值的有序映射，未命中的字段不包含在内
     */
    public static Map<String, Object> extract(String text, List<FieldRule> rules) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (text == null || text.isEmpty() || rules == null) {
            return result;
        }
        for (FieldRule rule : rules) {
            Matcher matcher = rule.getPattern().matcher(text);
            String raw = null;
            // 依次匹配到第 N 次，取第 M 组；中途匹配失败则视为未命中
            for (int i = 0; i < rule.getMatchIndex(); i++) {
                if (!matcher.find()) {
                    raw = null;
                    break;
                }
                raw = matcher.groupCount() >= rule.getGroupIndex()
                        ? matcher.group(rule.getGroupIndex()) : matcher.group();
            }
            if (raw != null) {
                Object value = ValueConverter.convert(rule.getFieldName(), raw.trim(), rule.getValueType());
                if (value != null) {
                    result.put(rule.getFieldName(), value);
                }
            }
        }
        return result;
    }
}
