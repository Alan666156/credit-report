package com.workbuddy.pbccrc.engine;

import com.workbuddy.pbccrc.common.BizException;
import com.workbuddy.pbccrc.common.ResultCode;
import com.workbuddy.pbccrc.dao.RuleMapper;
import com.workbuddy.pbccrc.model.dto.RuleRow;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 配置规则缓存。
 *
 * <p>启动时从数据库加载全部配置规则并按类型分组缓存；任一正则编译失败立即阻断启动，
 * 不静默吞错，以保证线上解析规则始终可用且确定。缓存以 volatile 发布，支持热重载。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
@Slf4j
@Component
public class RuleCache {

    private final RuleMapper ruleMapper;
    private volatile Map<String, List<FieldRule>> byType = Map.of();

    /**
     * 构造方法，由 Spring 注入规则数据访问对象。
     *
     * @param ruleMapper 规则数据访问对象
     */
    public RuleCache(RuleMapper ruleMapper) {
        this.ruleMapper = ruleMapper;
    }

    /**
     * 启动后加载并重建规则缓存。
     */
    @PostConstruct
    public void reload() {
        this.byType = build(ruleMapper.selectAllRules());
        log.info("配置规则加载完成: {} 个类型, {} 条规则",
                byType.size(), byType.values().stream().mapToInt(List::size).sum());
    }

    /**
     * 按类型获取规则列表。
     *
     * @param type 规则类型编码
     * @return 对应规则列表，无配置时返回空列表
     */
    public List<FieldRule> rules(String type) {
        return byType.getOrDefault(type, List.of());
    }

    /**
     * 获取全部规则缓存。
     *
     * @return 类型到规则列表的映射
     */
    public Map<String, List<FieldRule>> all() {
        return byType;
    }

    /**
     * 由规则行构造缓存，供测试直接喂数据而不依赖数据库。
     *
     * <p>每条规则先做回溯风险校验，再编译正则：危险结构直接拒绝加载（与编译失败同语义，
     * 让服务起不来而不是让解析线程跑飞），可疑结构只记告警。</p>
     *
     * @param rows 规则行列表
     * @return 类型到不可变规则列表的映射
     * @throws BizException 任一正则存在回溯风险或编译失败时抛出
     */
    public static Map<String, List<FieldRule>> build(List<RuleRow> rows) {
        Map<String, List<FieldRule>> result = new LinkedHashMap<>();
        for (RuleRow row : rows) {
            checkSafety(row);
            try {
                FieldRule rule = new FieldRule(row.getType(), row.getName(), row.getValue(),
                        row.getValueIndex(), ValueType.of(row.getName()));
                result.computeIfAbsent(row.getType(), k -> new ArrayList<>()).add(rule);
            } catch (RuntimeException e) {
                throw new BizException(ResultCode.SYSTEM_ERROR,
                        "正则编译失败, model_value_id 对应规则=" + row.getName() + ", value=" + row.getValue());
            }
        }
        return result.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        e -> List.copyOf(e.getValue()),
                        (a, b) -> a, LinkedHashMap::new));
    }

    /**
     * 校验单条规则正则的回溯风险。
     *
     * <p>危险结构（嵌套无界重复、可空重复）拒绝加载：这类正则在几十个字符的输入上就会指数级
     * 回溯，而 {@code java.util.regex} 不支持超时也不响应中断，线上跑飞之后没有安全的止损手段。
     * 可疑结构（多处通配）只告警，保留可加载性。</p>
     *
     * @param row 规则行
     * @throws BizException 存在危险结构时抛出
     */
    private static void checkSafety(RuleRow row) {
        List<RegexSafetyChecker.Finding> findings = RegexSafetyChecker.inspect(row.getValue());
        if (findings.isEmpty()) {
            return;
        }
        List<String> blocking = new ArrayList<>();
        for (RegexSafetyChecker.Finding finding : findings) {
            if (finding.getRisk() == RegexSafetyChecker.Risk.DANGEROUS) {
                blocking.add(finding.getCode() + ": " + finding.getMessage());
            } else {
                log.warn("规则 {} 存在回溯风险（不阻断加载）: {} - {}",
                        row.getName(), finding.getCode(), finding.getMessage());
            }
        }
        if (!blocking.isEmpty()) {
            throw new BizException(ResultCode.SYSTEM_ERROR,
                    "规则正则存在回溯风险, 拒绝加载: 字段=" + row.getName()
                            + ", value=" + row.getValue() + ", 原因=" + String.join("; ", blocking));
        }
    }
}
