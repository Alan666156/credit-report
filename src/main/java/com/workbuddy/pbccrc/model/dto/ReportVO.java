package com.workbuddy.pbccrc.model.dto;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 报告列表项视图，用于后台分页列表展示。
 *
 * @author Alan
 * @date 2026-11-17
 */
@Data
public class ReportVO {
    private Integer id;
    private String customerName;
    private String customerIdCard;
    private String name;
    private String certNo;
    private String reportNo;
    private LocalDateTime queryTime;
    private LocalDateTime reportTime;
    private String status;
    private Integer retryCount;
    private String errorMsg;
    private String appNo;
    private LocalDateTime createTime;
}
