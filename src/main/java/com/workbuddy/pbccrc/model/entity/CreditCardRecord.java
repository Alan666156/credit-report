package com.workbuddy.pbccrc.model.entity;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
/**
 * 信用卡明细实体，对应 t_pbccrc_credit_card_record。
 *
 * @author Alan
 * @date 2026-11-17
 */
@Data
public class CreditCardRecord {
    private Integer id;
    private Integer reportId;
    private LocalDateTime grantMoneyDate;
    private String grantOrganizations;
    private String currencyType;
    private LocalDateTime deadlineDate;
    private BigDecimal creditLimitMoney;
    private BigDecimal alreadyUseMoney;
    private BigDecimal overdueMoney;
    private Character bad;
    private Integer overdueMonth;
    private Integer overdueMonthNum;
    private Integer creditCardSixty;
    private Integer creditCardNinety;
    private Character active;
    private BigDecimal overdraftBalance;
    private Character closeAccount;
    private Character type;
    private String creditType;
    private Integer recordNum;
    private LocalDateTime createTime;
    private String creatorId;
    private String content;
    private LocalDateTime modifyTime;
    private String modifierId;
}
