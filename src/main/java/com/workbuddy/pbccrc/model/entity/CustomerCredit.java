package com.workbuddy.pbccrc.model.entity;
import lombok.Data;
import java.time.LocalDateTime;
/**
 * 信用判断结果实体，对应 t_pbccrc_customer_credit。
 *
 * @author Alan
 * @date 2026-11-17
 */
@Data
public class CustomerCredit {
    private Integer id;
    private Integer reportId;
    private LocalDateTime createTime;
    private String creatorId;
    private LocalDateTime modifyTime;
    private String modifierId;
    private String result;
    private Character type;
    private Character status;
    private String memo;
    private Integer creditLimitMoney;
    private Integer alreadyUseMoney;
    private Integer threeMonthQueryCount;
    private Integer oneMonthQueryCount;
    private Integer creditCard;
    private Integer zcreditCard;
    private Integer overdueCreditCard;
    private Integer overdueCreditCardMonth;
    private Integer overdueCreditCardNinety;
    private Integer overdueZcreditCard;
    private Integer overdueZcreditCardMonth;
    private Integer overdueZcreditCardNinety;
    private Integer loanCount;
    private Integer overdueLoanCount;
    private Integer overdueLoanMonth;
    private Integer overdueLoanNinety;
}
