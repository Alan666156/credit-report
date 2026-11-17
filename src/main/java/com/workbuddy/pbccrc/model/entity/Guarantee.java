package com.workbuddy.pbccrc.model.entity;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
/**
 * 为他人担保实体，对应 t_pbccrc_guarantee。
 *
 * @author Alan
 * @date 2026-11-17
 */
@Data
public class Guarantee {
    private Integer id;
    private Integer reportId;
    private String guaranteeName;
    private String idType;
    private String idCard;
    private String org;
    private BigDecimal contractAmount;
    private BigDecimal guaranteeAmount;
    private LocalDateTime guaranteeDate;
    private BigDecimal balance;
    private LocalDateTime deadlineDate;
    private String type;
    private String content;
    private Integer recordNum;
    private String creatorId;
    private LocalDateTime createTime;
    private String modifierId;
    private LocalDateTime modifyTime;
}
