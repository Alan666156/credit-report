package com.workbuddy.pbccrc.model.dto;

import com.workbuddy.pbccrc.model.entity.*;
import lombok.Data;
import java.util.List;

/**
 * 报告详情聚合视图，供后台查询单份报告的全量结果。
 *
 * <p>包含报告主表与信用卡、贷款、查询记录、资产处置、代偿、担保、行政处罚、
 * 民事判决、法院判决、欠税、电信欠费、信贷原文等各类明细。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
@Data
public class ReportDetailVO {
    private Report report;
    private List<CreditCardRecord> creditCards;
    private List<LoanRecord> loans;
    private List<QueryRecord> queryRecords;
    private List<AssetDisposal> assetDisposals;
    private List<Compensate> compensates;
    private List<Guarantee> guarantees;
    private List<AdminPunishment> adminPunishments;
    private List<CcivilDecision> civilDecisions;
    private List<CourtDecision> courtDecisions;
    private List<TaxArrears> taxArrears;
    private List<TelecomOwe> telecomOwes;
    private List<CreditRecord> rawCreditRecords;
}
