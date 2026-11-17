package com.workbuddy.pbccrc.model.dto;

import lombok.Data;

/**
 * 配置规则的扁平行，由模板主表与值表联查得到。
 *
 * <p>包含模板类型、字段名、正则与取值位置 {@code valueIndex}。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
@Data
public class RuleRow {
    private String type;
    private String name;
    private String value;
    private String valueIndex;
}
