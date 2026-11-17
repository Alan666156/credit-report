package com.workbuddy.pbccrc.model.entity;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
/**
 * 贷款明细实体，对应 t_pbccrc_loan_record。
 *
 * @author Alan
 * @date 2026-11-17
 */
@Data
public class LoanRecord {
    private Integer id;
    private Integer reportId;
    private LocalDateTime grantMoneyDate;
    private String grantOrganizations;
    private BigDecimal grantMoney;
    private String currencyType;
    private LocalDateTime deadlineDate;
    private LocalDateTime expireDate;
    private LocalDateTime settlementDate;
    private BigDecimal overdueMoney;
    private Integer overdueMonth;
    private Integer overdueMonthNum;
    private Character settlement;
    private Character bad;
    private Character type;
    private String creditType;
    private Integer recordNum;
    private LocalDateTime createTime;
    private String creatorId;
    private BigDecimal balance;
    private String loanType;
    private String orgType;
    private String content;
    private LocalDateTime modifyTime;
    private String modifierId;
}
