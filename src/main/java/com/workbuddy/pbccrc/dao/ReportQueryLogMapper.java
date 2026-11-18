package com.workbuddy.pbccrc.dao;

import org.apache.ibatis.annotations.Param;

/**
 * 提交幂等流水数据访问接口。
 *
 * <p>仅作提交流水记录，一次成功提交记一条。提交幂等不依赖本表：由 Redis（必需依赖）
 * 「姓名 + 证件号」7 天 TTL 原子锁保证，并在事务内以报告主表近 7 天窗口查询二次校验。
 * 唯一键冲突只忽略、不拦截，避免 7 天窗口过后同一客户再次提交被误挡。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
public interface ReportQueryLogMapper {

    /**
     * 忽略冲突地插入一条提交流水（冲突仅记录失败，不作为幂等判定）。
     *
     * @param customerName   客户姓名
     * @param customerIdCard 客户证件号
     * @param reportId       报告主键
     * @param creatorId      创建人
     * @return 影响行数，0 表示唯一键冲突
     */
    int insertIgnore(@Param("customerName") String customerName, @Param("customerIdCard") String customerIdCard, @Param("reportId") Integer reportId, @Param("creatorId") String creatorId);

    /**
     * 按报告主键删除提交流水。
     *
     * @param reportId 报告主键
     * @return 影响行数
     */
    int deleteByReportId(@Param("reportId") Integer reportId);
}
