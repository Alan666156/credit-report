package com.workbuddy.pbccrc.model.entity;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
/**
 * 行政处罚实体，对应 t_pbccrc_admin_punishment。
 *
 * @author Alan
 * @date 2026-11-17
 */
@Data
public class AdminPunishment {
    private Integer id;
    private Integer reportId;
    private String org;
    private String caseNo;
    private String punishment;
    private String reconsider;
    private String reconsiderResult;
    private LocalDateTime openDate;
    private LocalDateTime deadlineDate;
    private BigDecimal punishmentAmount;
    private String content;
    private String creatorId;
    private LocalDateTime createTime;
    private String modifierId;
    private LocalDateTime modifyTime;
}
