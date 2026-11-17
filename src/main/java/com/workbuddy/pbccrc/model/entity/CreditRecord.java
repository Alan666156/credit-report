package com.workbuddy.pbccrc.model.entity;
import lombok.Data;
import java.time.LocalDateTime;
/**
 * 信贷原文记录实体，对应 t_pbccrc_credit_record，保留每条信贷 li 的原始文本。
 *
 * @author Alan
 * @date 2026-11-17
 */
@Data
public class CreditRecord {
    private Integer id;
    private Integer reportId;
    private String type;
    private String content;
    private Integer recordNum;
    private String creatorId;
    private LocalDateTime createTime;
    private String modifierId;
    private LocalDateTime modifyTime;
}
