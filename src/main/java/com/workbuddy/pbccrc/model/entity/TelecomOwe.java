package com.workbuddy.pbccrc.model.entity;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
/**
 * 电信欠费实体，对应 t_pbccrc_telecom_owe。
 *
 * @author Alan
 * @date 2026-11-17
 */
@Data
public class TelecomOwe {
    private Integer id;
    private Integer reportId;
    private String carrier;
    private String type;
    private LocalDateTime chargeDate;
    private LocalDateTime openDate;
    private BigDecimal arrearsAmount;
    private String content;
    private String creatorId;
    private LocalDateTime createTime;
    private String modifierId;
    private LocalDateTime modifyTime;
}
