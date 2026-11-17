package com.workbuddy.pbccrc.model.entity;

import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 征信查询流水实体，姓名加证件号唯一，用于提交幂等与查询入口。
 *
 * @author Alan
 * @date 2026-11-17
 */
@Data
public class ReportQueryLog {
    private Integer id;
    private String customerName;
    private String customerIdCard;
    private LocalDate createTime;
    private Integer reportId;
    private String customerIdCardBak;
    private String customerNameBak;
    private String creatorId;
    private String modifierId;
    private LocalDateTime modifyTime;
}
