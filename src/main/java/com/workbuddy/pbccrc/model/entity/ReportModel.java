package com.workbuddy.pbccrc.model.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 解析模板主表实体，定义一类解析规则的类型与名称。
 *
 * @author Alan
 * @date 2026-11-17
 */
@Data
public class ReportModel {
    private Integer id;
    /** 1信用卡 2贷款 3资产处置 4代偿 5担保 6担保(卡) 7担保(贷款) */
    private String type;
    private String name;
    private LocalDateTime createdate;
    private String creatorId;
    private LocalDateTime createTime;
    private String modifierId;
    private LocalDateTime modifyTime;
}
