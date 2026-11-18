package com.workbuddy.pbccrc.dao;

import com.workbuddy.pbccrc.model.entity.CustomerHistory;

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
    int insert(CustomerHistory history);
}
