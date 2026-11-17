package com.workbuddy.pbccrc.model.entity;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
/**
 * 欠税信息实体，对应 t_pbccrc_tax_arrears。
 *
 * @author Alan
 * @date 2026-11-17
 */
@Data
public class TaxArrears {
    private Integer id;
    private Integer reportId;
    private String org;
    private String identifyNumber;
    private LocalDateTime taxArrearsDate;
    private BigDecimal taxArrearsAmount;
    private String content;
    private String creatorId;
    private LocalDateTime createTime;
    private String modifierId;
    private LocalDateTime modifyTime;
}
