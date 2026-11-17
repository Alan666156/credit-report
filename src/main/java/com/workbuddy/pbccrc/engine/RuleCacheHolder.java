package com.workbuddy.pbccrc.engine;

import com.workbuddy.pbccrc.model.enums.ParseType;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 解析类型到规则列表的便捷入口。
 *
 * <p>对 {@link RuleCache} 做一层薄封装，使各解析器直接以枚举 {@link ParseType}
 * 取规则，避免到处硬编码类型编码字符串。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
@Component
public class RuleCacheHolder {

    private final RuleCache cache;

    /**
     * 构造方法，由 Spring 注入规则缓存。
     *
     * @param cache 配置规则缓存
     */
    public RuleCacheHolder(RuleCache cache) {
        this.cache = cache;
    }

    /**
     * 按解析类型获取规则列表。
     *
     * @param type 解析类型枚举
     * @return 对应规则列表，无配置时返回空列表
     */
    public List<FieldRule> of(ParseType type) {
        return cache.rules(type.getCode());
    }
}
