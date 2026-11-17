package com.workbuddy.pbccrc.model.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 解析类型枚举，对应 {@code t_pbccrc_report_model.type}。
 *
 * <p>每个类型关联一个编码与中文描述，{@link #of(String)} 支持按编码反查。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
@Getter
@AllArgsConstructor
public enum ParseType {
    CREDIT_CARD("1", "信用卡"),
    LOAN("2", "贷款"),
    ASSET_DISPOSAL("3", "资产处置"),
    COMPENSATE("4", "保证人代偿"),
    GUARANTEE("5", "为他人担保"),
    GUARANTEE_CARD("6", "担保信用卡"),
    GUARANTEE_LOAN("7", "担保贷款");

    private final String code;
    private final String desc;

    /**
     * 按编码反查解析类型。
     *
     * @param code 类型编码
     * @return 对应的解析类型
     * @throws IllegalArgumentException 编码未知时抛出
     */
    public static ParseType of(String code) {
        for (ParseType t : values()) {
            if (t.code.equals(code)) {
                return t;
            }
        }
        throw new IllegalArgumentException("未知解析类型: " + code);
    }
}
