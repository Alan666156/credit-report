package com.workbuddy.pbccrc.dao;

import com.workbuddy.pbccrc.model.dto.RuleRow;

import java.util.List;

/**
 * 配置驱动规则数据访问接口。
 *
 * <p>从配置模板主表与值表联查全部解析规则，供 {@link com.workbuddy.pbccrc.engine.RuleCache}
 * 在启动时加载。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
public interface RuleMapper {

    /**
     * 查询全部配置规则。
     *
     * @return 规则行列表
     */
    List<RuleRow> selectAllRules();
}
