package com.workbuddy.pbccrc.dao;

import com.workbuddy.pbccrc.model.entity.CustomerHistory;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;

/**
 * 客户查询历史留痕数据访问接口。
 *
 * <p>后台每次带操作人的查询都会写入一条客户查询历史，用于审计追溯。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
public interface CustomerHistoryMapper {

    /**
     * 插入一条查询历史记录。
     *
     * @param history 客户查询历史实体
     * @return 影响行数，成功为 1
     */
    @Insert("INSERT INTO t_pbccrc_customer_history (report_id, query_date, customer_id_card, customer_name, operator, report_state, create_time, creator_id) VALUES (#{reportId}, #{queryDate}, #{customerIdCard}, #{customerName}, #{operator}, #{reportState}, #{createTime}, #{creatorId})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(CustomerHistory history);
}
