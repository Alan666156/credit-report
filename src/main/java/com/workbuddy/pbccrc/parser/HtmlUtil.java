package com.workbuddy.pbccrc.parser;

/**
 * HTML 文本清洗工具。
 *
 * <p>库里原文含字面换行符与不间断空格 {@code &nbsp;}，统一清洗为普通空格，
 * 便于后续正则匹配与关键词判断。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
public final class HtmlUtil {

    private HtmlUtil() {
    }

    /**
     * 清洗文本：将换行与不间断空格替换为普通空格，压缩连续空白并去首尾。
     *
     * @param s 原始文本，可为空
     * @return 清洗后的文本，入参为空时返回空串
     */
    public static String clean(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\n", " ")
                .replace("\u00a0", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * 安全包含判断。
     *
     * @param text 待判断文本，可为空
     * @param word 目标词
     * @return 文本非空且包含目标词时返回 true
     */
    public static boolean contains(String text, String word) {
        return text != null && text.contains(word);
    }

    /**
     * 取指定标签冒号后的值。
     *
     * @param text  文本
     * @param label 标签（含冒号）
     * @return 标签后的值，为空或不包含标签时返回 null
     */
    public static String afterColon(String text, String label) {
        if (text == null || !text.contains(label)) {
            return null;
        }
        String v = text.substring(text.indexOf(label) + label.length()).trim();
        return v.isEmpty() ? null : v;
    }
}
