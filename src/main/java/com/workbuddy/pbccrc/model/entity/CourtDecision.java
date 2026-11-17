package com.workbuddy.pbccrc.model.entity;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
/**
 * 法院强制执行实体，对应 t_pbccrc_court_decision。
 *
 * @author Alan
 * @date 2026-11-17
 */
@Data
public class CourtDecision {
    private Integer id;
    private Integer reportId;
    private String org;
    private String caseNo;
    private String reason;
    private String settleWay;
    private LocalDateTime registerDate;
    private LocalDateTime settleDate;
    private String status;
    private String applyExcuteObject;
    private String alreadyExcuteObject;
    private BigDecimal applyExcuteAmount;
    private BigDecimal alreadyExcuteAmount;
    private String content;
    private String creatorId;
    private LocalDateTime createTime;
    private String modifierId;
    private LocalDateTime modifyTime;
}
