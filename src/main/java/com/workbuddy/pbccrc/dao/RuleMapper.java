package com.workbuddy.pbccrc.dao;

import com.workbuddy.pbccrc.model.dto.RuleRow;
import org.apache.ibatis.annotations.Select;

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
    @Select("SELECT m.type, m.name, v.value, v.value_index FROM t_pbccrc_report_model m JOIN t_pbccrc_report_model_value v ON v.report_model_id = m.id ORDER BY v.id")
    List<RuleRow> selectAllRules();
}
