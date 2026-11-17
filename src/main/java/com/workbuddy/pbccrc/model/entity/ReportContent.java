package com.workbuddy.pbccrc.model.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 报告原文实体，与主表通过 reportId 关联存放征信报告 HTML。
 *
 * @author Alan
 * @date 2026-11-17
 */
@Data
public class ReportContent {
    private Integer id;
    private Integer reportId;
    private String content;
    private String creatorId;
    private LocalDateTime createTime;
    private String modifierId;
    private LocalDateTime modifyTime;
}
