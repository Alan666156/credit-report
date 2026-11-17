package com.workbuddy.pbccrc.model.entity;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
/**
 * 保证人代偿实体，对应 t_pbccrc_compensate。
 *
 * @author Alan
 * @date 2026-11-17
 */
@Data
public class Compensate {
    private Integer id;
    private Integer reportId;
    private LocalDateTime disposeDate;
    private String org;
    private BigDecimal amount;
    private BigDecimal balance;
    private String content;
    private Integer recordNum;
    private String creatorId;
    private LocalDateTime createTime;
    private String modifierId;
    private LocalDateTime modifyTime;
}
