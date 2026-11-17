package com.workbuddy.pbccrc.model.entity;
import lombok.Data;
import java.time.LocalDateTime;
/**
 * 查询记录实体，对应 t_pbccrc_query_record。
 *
 * @author Alan
 * @date 2026-11-17
 */
@Data
public class QueryRecord {
    private Integer id;
    private Integer reportId;
    private String queryDate;
    /** 1机构 2个人 */
    private String type;
    private String queryNo;
    private String operator;
    private String queryReason;
    private String queryOperator;
    private String creatorId;
    private LocalDateTime createTime;
    private String modifierId;
    private LocalDateTime modifyTime;
}
