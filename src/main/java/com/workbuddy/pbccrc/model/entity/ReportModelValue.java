package com.workbuddy.pbccrc.model.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 解析表达式值实体，存放一条规则正则与取值位置。
 *
 * <p>其中 {@code valueIndex} 语义为「组序号:第几次匹配」。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
@Data
public class ReportModelValue {
    private Integer id;
    private Integer reportModelId;
    private String value;
    private String valueIndex;
    private LocalDateTime createdate;
    private String creatorId;
    private String modifierId;
    private LocalDateTime modifyTime;
    private LocalDateTime createTime;
}
