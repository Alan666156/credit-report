package com.workbuddy.pbccrc.model.entity;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
/**
 * 民事判决实体，对应 t_pbccrc_ccivil_decision。
 *
 * @author Alan
 * @date 2026-11-17
 */
@Data
public class CcivilDecision {
    private Integer id;
    private Integer reportId;
    private String org;
    private String caseNo;
    private String reason;
    private String settleWay;
    private LocalDateTime registerDate;
    private LocalDateTime decisionDate;
    private String decisionResult;
    private String lawsuitObject;
    private BigDecimal lawsuitObjectAmount;
    private String content;
    private String creatorId;
    private LocalDateTime createTime;
    private String modifierId;
    private LocalDateTime modifyTime;
}
