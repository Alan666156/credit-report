package com.workbuddy.pbccrc.util;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * SQL 列名工具类。
 *
 * <p>通过反射从实体中提取需要写入的字段（排除合成字段、serialVersionUID 与 id），
 * 并将驼峰属性名转换为下划线列名，供通用明细写入器生成 SQL。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
public final class SqlColumnUtil {

    private SqlColumnUtil() {
    }

    /**
     * 提取实体中需要写入数据库的字段。
     *
     * @param clazz 实体类
     * @return 可写字段列表
     */
    public static List<Field> fields(Class<?> clazz) {
        List<Field> list = new ArrayList<>();
        for (Field f : clazz.getDeclaredFields()) {
            if (f.isSynthetic() || "serialVersionUID".equals(f.getName()) || "id".equals(f.getName())) {
                continue;
            }
            list.add(f);
        }
        return list;
    }

    /**
     * 驼峰命名转为下划线列名。
     *
     * @param name 驼峰属性名
     * @return 下划线列名
     */
    public static String toColumn(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            if (Character.isUpperCase(c)) {
                sb.append('_').append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
