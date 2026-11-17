package com.workbuddy.pbccrc.dao;

import org.apache.ibatis.annotations.DeleteProvider;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 明细表通用读写接口。
 *
 * <p>表名由服务层传入白名单常量，SQL 由 {@link DetailSqlProvider} 动态生成。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
public interface DetailMapper {

    /**
     * 按报告主键删除指定明细表的旧记录。
     *
     * @param table    目标明细表名（须在白名单内）
     * @param reportId 报告主键
     * @return 影响行数
     */
    @DeleteProvider(type = DetailSqlProvider.class, method = "deleteByReportId")
    int deleteByReportId(@Param("table") String table, @Param("reportId") Integer reportId);

    /**
     * 向指定明细表批量插入记录，列由实体反射生成。
     *
     * @param table 目标明细表名（须在白名单内）
     * @param list  待插入实体列表
     * @return 影响行数
     */
    @InsertProvider(type = DetailSqlProvider.class, method = "insertBatch")
    int insertBatch(@Param("table") String table, @Param("list") List<?> list);
}
