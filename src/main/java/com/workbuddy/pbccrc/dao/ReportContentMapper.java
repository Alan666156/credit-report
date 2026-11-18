package com.workbuddy.pbccrc.dao;

import com.workbuddy.pbccrc.model.entity.ReportContent;
import org.apache.ibatis.annotations.Param;

/**
 * 报告原文数据访问接口。
 *
 * <p>报告 HTML 原文单独存表，与主表通过 {@code report_id} 关联。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
public interface ReportContentMapper {

    /**
     * 按报告主键查询原文。
     *
     * @param reportId 报告主键
     * @return 报告原文实体，无记录返回 null
     */
    ReportContent findByReportId(@Param("reportId") Integer reportId);

    /**
     * 插入报告原文。
     *
     * @param content 报告原文实体
     * @return 影响行数，成功为 1
     */
    int insert(ReportContent content);

    /**
     * 按报告主键删除原文。
     *
     * @param reportId 报告主键
     * @return 影响行数
     */
    int deleteByReportId(@Param("reportId") Integer reportId);
}
