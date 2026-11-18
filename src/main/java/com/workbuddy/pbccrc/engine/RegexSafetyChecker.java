package com.workbuddy.pbccrc.engine;

import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * 正则回溯风险静态校验。
 *
 * <p>规则由人工在配置表维护，一条写错的正则（如 {@code (a+)+}、{@code (.*)*}）遇到稍长文本
 * 就会指数级回溯，单条规则足以把一个解析线程钉死；而 {@code java.util.regex} 既不支持匹配
 * 超时，也不响应线程中断，线上只能等它自己跑完（看门狗能发现卡死，但杀不掉）。这里把检查
 * 提前到规则加载期：结构上有爆炸风险的正则直接拒绝加载，让服务起不来，而不是让解析线程跑飞。</p>
 *
 * <p>检查项：</p>
 * <ul>
 *   <li>{@code NESTED_REPEAT}（阻断）：被重复修饰的组内部含无界量词，如 {@code (a+)+}、
 *       {@code (.*a){8}}——切分方式随输入长度组合增长。</li>
 *   <li>{@code EMPTY_REPEAT}（阻断）：被重复修饰的组本身可匹配空串，如 {@code (a?)+}、
 *       {@code (a|)*}——每一轮都能不消耗字符地「成功」，等价于无穷多种切分方式。</li>
 *   <li>{@code MULTI_WILDCARD}（告警）：同一模式内出现两处及以上 {@code .*}／{@code .+}，
 *       长文本下退化为多项式级回溯，通常是写法可优化的信号，但不必然爆炸，故不阻断。</li>
 * </ul>
 *
 * <p>阈值不是拍脑袋定的。用统计 {@code charAt} 次数的输入实测（29 个字符）：
 * {@code (.*a){2}} 1.7 万次、{@code (.*a){4}} 64 万次、{@code (.*a){6}} 997 万次、
 * {@code (.*a){8}} 7891 万次——有界重复只要重复次数到 3，配合可「吞掉」可变长度的组，
 * 就足以在单条短文本上烧掉数百毫秒。因此嵌套重复的阻断线定在重复 3 次。</p>
 *
 * <p>需要说明的是，HotSpot 对部分经典形态（如 {@code (a+)+}）有内建优化，同一输入只产生
 * 千余次读取。但那是实现细节而非安全保证：规则可能被移植、JDK 可能升级、模式稍改即失效，
 * 检测仍按结构判定，宁可拦住可写的等价写法。可空重复的爆炸需要重复次数足够多，阈值定在
 * 10 次，避免对 {@code (a?){3}} 这类无害写法报错。</p>
 *
 * <p>不做交替项前缀重叠检查（如 {@code (a|ab)+}）：判定需要完整的首字符集推导，误报率高，
 * 且现有规则中未出现该形态，留给评审与样本回归。</p>
 *
 * <p>本类为轻量扫描，不校验正则语法（语法错误交给 {@link java.util.regex.Pattern#compile}），
 * 遇到无法解析的片段就保守跳过，不抛异常、不影响正常规则加载。</p>
 *
 * @author Alan
 * @date 2026-11-18
 */
public final class RegexSafetyChecker {

    private RegexSafetyChecker() {
    }

    /**
     * 风险等级。
     */
    public enum Risk {
        /** 危险：必然或极可能指数级回溯，拒绝加载。 */
        DANGEROUS,
        /** 可疑：长文本下成本偏高，仅告警。 */
        SUSPICIOUS
    }

    /**
     * 一条校验发现。
     */
    @Getter
    public static final class Finding {
        private final Risk risk;
        private final String code;
        private final String message;

        /**
         * 构造方法。
         *
         * @param risk    风险等级
         * @param code    检查项编码，便于日志与告警归类
         * @param message 人类可读的说明，含触发的片段与量词
         */
        public Finding(Risk risk, String code, String message) {
            this.risk = risk;
            this.code = code;
            this.message = message;
        }
    }

    /**
     * 扫描一条正则的回溯风险。
     *
     * @param regex 正则表达式，可为空
     * @return 发现列表，按出现顺序返回；无风险时为空列表
     */
    public static List<Finding> inspect(String regex) {
        List<Finding> findings = new ArrayList<>();
        if (regex == null || regex.isEmpty()) {
            return findings;
        }
        Scanner scanner = new Scanner(regex, findings);
        try {
            scanner.parse();
        } catch (RuntimeException e) {
            // 扫描器只做启发式判断，解析不了说明写法超出它的认知范围：
            // 保持已收集的结论，语法问题由 Pattern.compile 负责报错
        }
        return findings;
    }

    /**
     * 判断发现列表里是否存在需要阻断加载的危险项。
     *
     * @param findings 校验发现
     * @return 存在危险项返回 true
     */
    public static boolean hasDangerous(List<Finding> findings) {
        return findings.stream().anyMatch(f -> f.getRisk() == Risk.DANGEROUS);
    }

    /**
     * 轻量扫描器：只跟踪「组 + 量词 + 可空性 + 是否含无界量词」，不做完整语法解析。
     */
    private static final class Scanner {

        /** 嵌套重复的阻断阈值：组内含无界量词时，外层重复达到该次数即视为危险。 */
        private static final int NESTED_REPEAT_THRESHOLD = 3;

        /** 可空重复的阻断阈值：单轮不消耗字符，需要足够多轮才能放大到事故量级。 */
        private static final int EMPTY_REPEAT_THRESHOLD = 10;

        private final String src;
        private final int length;
        private final List<Finding> findings;

        private int pos;
        private int wildcardCount;

        /**
         * 构造方法。
         *
         * @param src      正则源码
         * @param findings 结论收集容器
         */
        private Scanner(String src, List<Finding> findings) {
            this.src = src;
            this.length = src.length();
            this.findings = findings;
        }

        /**
         * 从开头解析整个模式并输出结论。
         */
        void parse() {
            parseAlternation();
            if (wildcardCount >= 2) {
                findings.add(new Finding(Risk.SUSPICIOUS, "MULTI_WILDCARD",
                        "出现 " + wildcardCount + " 处 .*/.+，长文本下回溯量随长度多项式增长，"
                                + "建议改用更精确的字符类或锚点收窄"));
            }
        }

        /**
         * 解析交替表达式：{@code a|b|c}。
         *
         * @return 该层序列的可空性与量词信息
         */
        private Node parseAlternation() {
            Node result = parseSequence();
            while (pos < length && src.charAt(pos) == '|') {
                pos++;
                Node branch = parseSequence();
                result.canBeEmpty |= branch.canBeEmpty;
                result.hasUnbounded |= branch.hasUnbounded;
            }
            return result;
        }

        /**
         * 解析一段顺序连接的原子序列，并在每个量词之后做重复检查。
         *
         * @return 该段序列的可空性与量词信息
         */
        private Node parseSequence() {
            Node seq = new Node();
            seq.canBeEmpty = true;
            while (pos < length) {
                char c = src.charAt(pos);
                if (c == '|' || c == ')') {
                    break;
                }
                Node atom = parseAtom();
                Quantifier q = parseQuantifier();
                if (q != null) {
                    if (atom.isDot && q.unbounded()) {
                        wildcardCount++;
                    }
                    checkRepeat(atom, q);
                    if (q.min == 0) {
                        // 量词允许零次，该原子整体可空（(a?)+、(a*)+ 的根因）
                        atom.canBeEmpty = true;
                    }
                    if (q.unbounded()) {
                        seq.hasUnbounded = true;
                        atom.hasRepeat = true;
                    }
                }
                seq.canBeEmpty &= atom.canBeEmpty;
                seq.hasUnbounded |= atom.hasUnbounded || atom.hasRepeat;
            }
            return seq;
        }

        /**
         * 检查「组被重复修饰」是否构成回溯爆炸。
         *
         * <p>嵌套重复与可空重复的阻断线不同：前者重复 3 次就能把成本放大到百万级读取，
         * 后者要重复 10 次以上才有量级，故分开判定。</p>
         *
         * @param atom 被修饰的原子
         * @param q    量词
         */
        private void checkRepeat(Node atom, Quantifier q) {
            if (!atom.isGroup) {
                return;
            }
            if (atom.hasUnbounded && q.nestedRisky()) {
                findings.add(new Finding(Risk.DANGEROUS, "NESTED_REPEAT",
                        "组 " + atom.text + " 内部含无界量词，外层又用 " + q.text
                                + " 重复，切分方式随输入长度组合增长"));
            } else if (atom.canBeEmpty && q.emptyRisky()) {
                findings.add(new Finding(Risk.DANGEROUS, "EMPTY_REPEAT",
                        "组 " + atom.text + " 可匹配空串，用 " + q.text
                                + " 重复时每一轮都能不消耗字符地成功，回溯无上界"));
            }
        }

        /**
         * 解析单个原子：分组、字符类、转义、通配、零宽断言或字面量。
         *
         * @return 原子信息
         */
        private Node parseAtom() {
            char c = src.charAt(pos);
            if (c == '(') {
                return parseGroup();
            }
            if (c == '[') {
                skipCharClass();
                return new Node();
            }
            if (c == '\\') {
                return parseEscape();
            }
            if (c == '.') {
                pos++;
                Node dot = new Node();
                dot.isDot = true;
                return dot;
            }
            if (c == '^' || c == '$') {
                pos++;
                return Node.zeroWidth();
            }
            pos++;
            return new Node();
        }

        /**
         * 解析分组，区分普通组与零宽断言组。
         *
         * @return 分组信息，断言组整体视为可空且不参与外层重复检查
         */
        private Node parseGroup() {
            int start = pos;
            pos++;
            boolean lookaround = false;
            if (pos < length && src.charAt(pos) == '?') {
                lookaround = skipGroupPrefix();
            }
            Node inner = parseAlternation();
            if (pos < length && src.charAt(pos) == ')') {
                pos++;
            }
            Node group = new Node();
            group.isGroup = !lookaround;
            group.canBeEmpty = lookaround || inner.canBeEmpty;
            group.hasUnbounded = inner.hasUnbounded;
            group.text = src.substring(start, Math.min(pos, length));
            return group;
        }

        /**
         * 消费 {@code (?} 之后的分组前缀，识别是否为断言组。
         *
         * @return 是零宽断言返回 true
         */
        private boolean skipGroupPrefix() {
            char k = pos + 1 < length ? src.charAt(pos + 1) : '\0';
            switch (k) {
                case ':' , '>':
                    pos += 2;
                    return false;
                case '=' , '!':
                    pos += 2;
                    return true;
                case '<':
                    char k2 = pos + 2 < length ? src.charAt(pos + 2) : '\0';
                    if (k2 == '=' || k2 == '!') {
                        pos += 3;
                        return true;
                    }
                    // 命名分组 (?<name>...)
                    int close = src.indexOf('>', pos);
                    pos = close > 0 ? close + 1 : pos + 2;
                    return false;
                default:
                    // (?i)、(?i:...) 等内联标志：整体跳过前缀，内部仍按普通内容解析
                    pos += 2;
                    return false;
            }
        }

        /**
         * 跳过字符类，类内的 {@code * + ? ( )} 都是字面量，不参与风险判定。
         */
        private void skipCharClass() {
            pos++;
            if (pos < length && src.charAt(pos) == '^') {
                pos++;
            }
            if (pos < length && src.charAt(pos) == ']') {
                pos++;
            }
            while (pos < length) {
                char c = src.charAt(pos);
                if (c == '\\') {
                    pos += 2;
                } else if (c == ']') {
                    pos++;
                    return;
                } else {
                    pos++;
                }
            }
        }

        /**
         * 解析转义序列，区分零宽断言与普通字符。
         *
         * @return 原子信息
         */
        private Node parseEscape() {
            pos++;
            if (pos >= length) {
                return new Node();
            }
            char c = src.charAt(pos);
            if (c == 'Q') {
                // \Q...\E 内部整体是字面量
                int end = src.indexOf("\\E", pos);
                pos = end < 0 ? length : end + 2;
                return new Node();
            }
            pos++;
            if (c == 'b' || c == 'B' || c == 'A' || c == 'z' || c == 'Z' || c == 'G') {
                return Node.zeroWidth();
            }
            return new Node();
        }

        /**
         * 解析量词：{@code * + ? {n} {n,} {n,m}}，并跳过其后的懒惰/占有修饰符。
         *
         * @return 量词信息，当前位置不是量词时返回 null
         */
        private Quantifier parseQuantifier() {
            if (pos >= length) {
                return null;
            }
            char c = src.charAt(pos);
            Quantifier q;
            if (c == '*') {
                pos++;
                q = new Quantifier(0, null, "*");
            } else if (c == '+') {
                pos++;
                q = new Quantifier(1, null, "+");
            } else if (c == '?') {
                pos++;
                q = new Quantifier(0, 1, "?");
            } else if (c == '{') {
                q = parseBraceQuantifier();
                if (q == null) {
                    return null;
                }
            } else {
                return null;
            }
            // 懒惰（*?）与占有（*+）只影响匹配策略，不影响重复次数
            if (pos < length && (src.charAt(pos) == '?' || src.charAt(pos) == '+')) {
                pos++;
            }
            return q;
        }

        /**
         * 解析花括号量词，格式非法时回退（此时 {@code {} 是字面量}）。
         *
         * @return 量词信息，非法返回 null
         */
        private Quantifier parseBraceQuantifier() {
            int start = pos;
            pos++;
            int min = readInt();
            if (min < 0) {
                pos = start;
                return null;
            }
            Integer max = min;
            if (pos < length && src.charAt(pos) == ',') {
                pos++;
                int upper = readInt();
                max = upper < 0 ? null : upper;
            }
            if (pos >= length || src.charAt(pos) != '}') {
                pos = start;
                return null;
            }
            pos++;
            return new Quantifier(min, max, src.substring(start, pos));
        }

        /**
         * 读取一段十进制数字。
         *
         * @return 读到的数值，当前位置不是数字返回 -1
         */
        private int readInt() {
            int start = pos;
            while (pos < length && Character.isDigit(src.charAt(pos))) {
                pos++;
            }
            if (pos == start) {
                return -1;
            }
            try {
                return Integer.parseInt(src.substring(start, pos));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
    }

    /**
     * 一个原子或序列在回溯层面的特征。
     */
    private static final class Node {

        /** 是否可匹配空串（零宽断言、{@code *} / {@code ?} 修饰过的原子均可空）。 */
        private boolean canBeEmpty;

        /** 内部是否含无界量词（含嵌套层级）。 */
        private boolean hasUnbounded;

        /** 本原子自身是否被无界量词修饰。 */
        private boolean hasRepeat;

        /** 是否为可被外层重复修饰的普通分组（断言组为 false）。 */
        private boolean isGroup;

        /** 是否为通配符 {@code .}。 */
        private boolean isDot;

        /** 分组源码片段，用于告警定位。 */
        private String text = "";

        /**
         * 构造一个可匹配空串的零宽原子（{@code ^ $}、{@code \b} 等断言）。
         *
         * @return 零宽原子
         */
        static Node zeroWidth() {
            Node node = new Node();
            node.canBeEmpty = true;
            return node;
        }
    }

    /**
     * 量词信息。
     */
    private static final class Quantifier {

        private final int min;
        private final Integer max;
        private final String text;

        /**
         * 构造方法。
         *
         * @param min  最少重复次数
         * @param max  最多重复次数，null 表示无上界
         * @param text 源码片段，用于告警
         */
        private Quantifier(int min, Integer max, String text) {
            this.min = min;
            this.max = max;
            this.text = text;
        }

        /**
         * 是否无上界。
         *
         * @return 无上界返回 true
         */
        boolean unbounded() {
            return max == null;
        }

        /**
         * 作为「组内已含无界量词」的外层重复，是否达到阻断线。
         *
         * @return 无上界或重复次数达到阈值返回 true
         */
        boolean nestedRisky() {
            return max == null || max >= Scanner.NESTED_REPEAT_THRESHOLD;
        }

        /**
         * 作为「组可匹配空串」的外层重复，是否达到阻断线。
         *
         * @return 无上界或重复次数达到阈值返回 true
         */
        boolean emptyRisky() {
            return max == null || max >= Scanner.EMPTY_REPEAT_THRESHOLD;
        }
    }
}
