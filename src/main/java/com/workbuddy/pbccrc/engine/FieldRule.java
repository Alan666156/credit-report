package com.workbuddy.pbccrc.engine;

import lombok.Getter;

import java.util.regex.Pattern;

/**
 * 一条可执行的字段抽取规则。
 *
 * <p>由类型、字段名、正则、捕获组序号、第几次匹配以及目标值类型构成。其中
 * {@code valueIndex} 采用「组序号:第几次匹配」格式，例如 {@code 1:2} 表示取第 1 组
 * 第 2 次匹配的结果；解析失败时回退为默认值（组 1、第 1 次匹配）。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
@Getter
public class FieldRule {
    private final String type;
    private final String fieldName;
    private final Pattern pattern;
    private final int groupIndex;
    private final int matchIndex;
    private final ValueType valueType;

    /**
     * 构造方法，编译正则并解析取值位置。
     *
     * @param type      规则所属类型（对应解析块的 type）
     * @param fieldName 目标字段名
     * @param regex     抽取用正则表达式
     * @param valueIndex 取值位置，格式为「组序号:第几次匹配」，可为空
     * @param valueType  抽取结果的目标值类型
     */
    public FieldRule(String type, String fieldName, String regex, String valueIndex, ValueType valueType) {
        this.type = type;
        this.fieldName = fieldName;
        this.pattern = Pattern.compile(regex);
        int group = 1;
        int match = 1;
        if (valueIndex != null && !valueIndex.isBlank()) {
            String[] parts = valueIndex.split(":");
            try {
                group = Integer.parseInt(parts[0].trim());
            } catch (NumberFormatException ignored) {
                group = 1;
            }
            if (parts.length > 1) {
                try {
                    match = Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException ignored) {
                    match = 1;
                }
            }
        }
        this.groupIndex = Math.max(group, 0);
        this.matchIndex = Math.max(match, 1);
        this.valueType = valueType;
    }
}
