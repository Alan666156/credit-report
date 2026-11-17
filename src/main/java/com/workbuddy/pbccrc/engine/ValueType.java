package com.workbuddy.pbccrc.engine;

/**
 * 抽取字段的目标值类型。
 *
 * <p>约定字段名后缀即可推断目标类型：日期、金额、标志位、整数，其余按字符串处理。
 * 新增字段只需遵守命名约定，或在 {@link #of(String)} 中补一行规则。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
public enum ValueType {
    STRING, MONEY, DATE, FLAG, INT;

    /**
     * 依据字段名约定推断值类型。
     *
     * @param fieldName 字段名，可为空
     * @return 推断出的值类型，默认返回 STRING
     */
    public static ValueType of(String fieldName) {
        String n = fieldName == null ? "" : fieldName.toUpperCase();
        if (n.endsWith("_DATE") || n.endsWith("DATE") || n.contains("_TIME")) {
            return DATE;
        }
        if (n.contains("MONEY") || n.contains("AMOUNT") || n.contains("BALANCE")
                || n.endsWith("_SUM") || n.contains("LIMIT")) {
            return MONEY;
        }
        if ("BAD".equals(n) || "SETTLEMENT".equals(n) || "ACTIVE".equals(n)
                || "CLOSE_ACCOUNT".equals(n)) {
            return FLAG;
        }
        if (n.endsWith("_MONTH") || n.endsWith("_MONTH_NUM") || n.contains("COUNT")
                || n.endsWith("_TOTAL") || n.endsWith("NINETY") || n.endsWith("SIXTY")) {
            return INT;
        }
        if (n.endsWith("_NUM")) {
            return INT;
        }
        return STRING;
    }
}
