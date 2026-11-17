package com.workbuddy.pbccrc.model.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 人行征信报告主表实体，承载报告头信息、各类汇总计数与解析状态。
 *
 * @author Alan
 * @date 2026-11-17
 */
@Data
public class Report {
    private Integer id;
    private String name;
    private String certType;
    private String certNo;
    private LocalDateTime queryTime;
    private LocalDateTime reportTime;
    private String maritalStatus;
    private String reportNo;
    private String description;
    private String creditRecordDescription;
    private String queryRecordDescription;
    private String commonRecordDescription;
    private String commonRecordDetail;
    private String creditRecordSummary;
    private Integer creditCardTotal;
    private Integer creditCardActive;
    private Integer creditCardOverdue;
    private Integer creditCardSeriousOverdue;
    private Integer creditCardGuarantee;
    private Integer housingLoanTotal;
    private Integer housingLoanActive;
    private Integer housingLoanOverdue;
    private Integer housingLoanSeriousOverdue;
    private Integer housingLoanGuarantee;
    private Integer otherLoanTotal;
    private Integer otherLoanActive;
    private Integer otherLoanOverdue;
    private Integer otherLoanSeriousOverdue;
    private Integer otherLoanGuarantee;
    private LocalDateTime createTime;
    private String creatorId;
    private LocalDateTime modifyTime;
    private String modifierId;
    private String customerIdCard;
    private String errorMsg;
    private String customerName;
    private Integer assetTotal;
    private Integer repaymentTotal;
    private String appNo;
    private String customerIdCardBak;
    private String customerNameBak;

    /** 以下字段由 db/migration.sql 补齐，用于异步状态机与幂等。 */
    private String status;
    private Integer retryCount;
    private LocalDateTime heartbeatTime;
    /** 报告正文全文 MD5，仅作内容完整性校验，不参与提交幂等。 */
    private String contentHash;
}
