package com.workbuddy.pbccrc.model.dto;

import lombok.Data;

/**
 * 后台管理：报告列表分页查询请求（可选条件）。
 *
 * <p>与 {@link ReportQueryRequest} 不同，本请求的所有查询条件均为可选，
 * 用于管理端不带身份约束地浏览全部报告，支持按用户名、姓名、创建时间区间过滤。</p>
 *
 * @author Alan
 * @date 2026-11-18
 */
@Data
public class ReportPageRequest {
    /** 用户名（对应提交时的客户姓名 customer_name），模糊匹配。 */
    private String customerName;

    /** 姓名（报告中解析出的姓名 name），模糊匹配。 */
    private String name;

    /** 创建时间起始，包含，格式 yyyy-MM-dd HH:mm:ss。 */
    private String createTimeStart;

    /** 创建时间截止，包含，格式 yyyy-MM-dd HH:mm:ss。 */
    private String createTimeEnd;

    private Integer pageNum = 1;
    private Integer pageSize = 10;
}
