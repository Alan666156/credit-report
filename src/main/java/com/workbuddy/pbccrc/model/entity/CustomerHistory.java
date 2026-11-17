package com.workbuddy.pbccrc.model.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 客户报告查询流水实体，对应 t_pbccrc_customer_history，用于查询留痕审计。
 *
 * @author Alan
 * @date 2026-11-17
 */
@Data
public class CustomerHistory {
    private Integer id;
    private Integer reportId;
    private LocalDateTime queryDate;
    private String customerIdCard;
    private String customerName;
    private String operator;
    private String reportState;
    private String customerIdCardBak;
    private String customerNameBak;
    private String idCard;
    private String creatorId;
    private LocalDateTime createTime;
    private String modifierId;
    private LocalDateTime modifyTime;
}
