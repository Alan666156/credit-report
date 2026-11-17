package com.workbuddy.pbccrc.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 报告提交请求。
 *
 * @author Alan
 * @date 2026-11-17
 */
@Data
public class SubmitRequest {
    @NotBlank(message = "身份证号不能为空")
    private String customerIdCard;

    @NotBlank(message = "姓名不能为空")
    private String customerName;

    @NotBlank(message = "报告HTML内容不能为空")
    @Size(max = 10 * 1024 * 1024, message = "报告HTML内容不能超过10MB")
    private String htmlContent;

    private String appNo;
    private String operator;
}
