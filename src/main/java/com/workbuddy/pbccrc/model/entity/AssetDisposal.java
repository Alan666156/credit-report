package com.workbuddy.pbccrc.model.entity;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
/**
 * 资产处置实体，对应 t_pbccrc_asset_disposal。
 *
 * @author Alan
 * @date 2026-11-17
 */
@Data
public class AssetDisposal {
    private Integer id;
    private Integer reportId;
    private BigDecimal amount;
    private BigDecimal balance;
    private LocalDateTime disposeDate;
    private String org;
    private LocalDateTime repaymentDate;
    private String content;
    private Integer recordNum;
    private String creatorId;
    private LocalDateTime createTime;
    private String modifierId;
    private LocalDateTime modifyTime;
}
