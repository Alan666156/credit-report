package com.workbuddy.pbccrc.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 按证件号加姓名查询报告的请求（后台管理）。
 *
 * @author Alan
 * @date 2026-11-17
 */
@Data
public class ReportQueryRequest {
    @NotBlank(message = "身份证号不能为空")
    private String customerIdCard;

    @NotBlank(message = "姓名不能为空")
    private String customerName;

    private Integer pageNum = 1;
    private Integer pageSize = 10;
    private String operator;
}
