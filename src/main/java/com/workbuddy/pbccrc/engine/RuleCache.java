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
     * @param rows 规则行列表
     * @return 类型到不可变规则列表的映射
     * @throws BizException 任一正则编译失败时抛出
     */
    public static Map<String, List<FieldRule>> build(List<RuleRow> rows) {
        Map<String, List<FieldRule>> result = new LinkedHashMap<>();
        for (RuleRow row : rows) {
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
}
