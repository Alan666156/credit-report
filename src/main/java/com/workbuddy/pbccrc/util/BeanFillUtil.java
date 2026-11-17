package com.workbuddy.pbccrc.util;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 字段填充工具。
 *
 * <p>将配置驱动解析得到的大写字段名（如 {@code GRANT_MONEY_DATE}）按别名映射
 * 并通过反射填充到实体的驼峰属性，必要时做类型转换；单字段失败不影响整份报告。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
public final class BeanFillUtil {

    private BeanFillUtil() {
    }

    /**
     * 将字段名到值的映射填充到实体对象。
     *
     * @param bean   待填充实体
     * @param values 字段名到值的映射
     * @param <T>    实体类型
     * @return 填充后的实体（与原对象相同）
     */
    public static <T> T fill(T bean, Map<String, Object> values) {
        if (bean == null || values == null) {
            return bean;
        }
        for (Map.Entry<String, Object> e : values.entrySet()) {
            String name = alias(e.getKey());
            Field field = findField(bean.getClass(), name);
            if (field == null) {
                continue;
            }
            try {
                field.setAccessible(true);
                field.set(bean, coerce(e.getValue(), field.getType()));
            } catch (IllegalAccessException ignored) {
                // 单字段填充失败不影响整份报告
            }
        }
        return bean;
    }

    /**
     * 将配置字段名映射为实体属性名，未命中别名规则时按通用驼峰转换。
     *
     * @param configName 配置字段名
     * @return 实体属性名
     */
    public static String alias(String configName) {
        switch (configName) {
            case "GRANT_DATE": return "grantMoneyDate";
            case "ASSET_DISPOSE_DATE": return "disposeDate";
            case "ASSET_ORG": return "org";
            case "ASSET_AMOUNT": return "amount";
            case "ASSET_BALANCE": return "balance";
            case "ASSET_REPAYMENT_DATE": return "repaymentDate";
            case "COMPENSATE_DISPOSE_DATE": return "disposeDate";
            case "COMPENSATE_ORG": return "org";
            case "COMPENSATE_AMOUNT": return "amount";
            case "COMPENSATE_BALANCE": return "balance";
            case "GUARANTEE_IDTYPE": return "idType";
            case "GUARANTEE_IDCARD": return "idCard";
            case "GUARANTEE_ORG": return "org";
            case "GUARANTEE_CONTRACT_AMOUNT": return "contractAmount";
            case "GUARANTEE_AMOUNT": return "guaranteeAmount";
            case "GUARANTEE_BALANCE": return "balance";
            case "GUARANTEE_DEADLINE_DATE": return "deadlineDate";
            case "GUARANTEE_TYPE": return "type";
            case "GRANT_AMOUNT": return "contractAmount";
            case "CREDITCARDUSED_AMOUNT": return "balance";
            case "CONTRACT_AMOUNT": return "contractAmount";
            case "BALANCE_AMOUNT": return "balance";
            case "GUARANTEE_DATE": return "guaranteeDate";
            case "GUARANTEE_NAME": return "guaranteeName";
            default: return toCamel(configName);
        }
    }

    /**
     * 通用下划线命名转驼峰命名。
     *
     * @param name 下划线命名
     * @return 驼峰命名
     */
    private static String toCamel(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        String[] parts = name.toLowerCase().split("_");
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                sb.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
            }
        }
        return sb.toString();
    }

    /**
     * 在类中按名称查找字段。
     *
     * @param clazz 目标类
     * @param name  字段名
     * @return 找到的字段，未找到返回 null
     */
    private static Field findField(Class<?> clazz, String name) {
        for (Field f : clazz.getDeclaredFields()) {
            if (f.getName().equals(name)) {
                return f;
            }
        }
        return null;
    }

    /**
     * 将值转换为目标属性的类型。
     *
     * @param value  原始值
     * @param target 目标属性类型
     * @return 转换后的值
     */
    private static Object coerce(Object value, Class<?> target) {
        if (value == null) {
            return null;
        }
        if (target.isInstance(value)) {
            return value;
        }
        String s = value.toString();
        if (target == String.class) {
            return s;
        }
        if (target == BigDecimal.class) {
            return new BigDecimal(s);
        }
        if (target == Integer.class || target == int.class) {
            return Integer.valueOf(s);
        }
        if (target == Character.class || target == char.class) {
            return s.isEmpty() ? null : s.charAt(0);
        }
        if (target == LocalDateTime.class && value instanceof LocalDateTime) {
            return value;
        }
        if (target == LocalDate.class && value instanceof LocalDateTime) {
            return ((LocalDateTime) value).toLocalDate();
        }
        return value;
    }
}
