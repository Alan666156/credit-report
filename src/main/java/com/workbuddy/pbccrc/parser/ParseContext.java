package com.workbuddy.pbccrc.parser;

import com.workbuddy.pbccrc.model.entity.*;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一次解析的可变上下文。
 *
 * <p>输入为报告原文与报告主键等基础信息，输出为报告主表汇总与各类明细列表；
 * 解析过程中出现的非致命降级逐条记入 {@link #warnings}，由流水线汇总打日志。
 * 这些告警不写入主表 {@code error_msg}——该字段只用于说明报告失败的原因。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
@Data
public class ParseContext {
    private Integer reportId;
    private String customerIdCard;
    private String customerName;
    private String appNo;
    private String html;
    private String contentHash;

    private Report report = new Report();
    private List<CreditCardRecord> creditCards = new ArrayList<>();
    private List<LoanRecord> loans = new ArrayList<>();
    private List<QueryRecord> queryRecords = new ArrayList<>();
    private List<AssetDisposal> assetDisposals = new ArrayList<>();
    private List<Compensate> compensates = new ArrayList<>();
    private List<Guarantee> guarantees = new ArrayList<>();
    private List<CreditRecord> rawCreditRecords = new ArrayList<>();
    private List<AdminPunishment> adminPunishments = new ArrayList<>();
    private List<CcivilDecision> civilDecisions = new ArrayList<>();
    private List<CourtDecision> courtDecisions = new ArrayList<>();
    private List<TaxArrears> taxArrears = new ArrayList<>();
    private List<TelecomOwe> telecomOwes = new ArrayList<>();

    private final List<String> warnings = new ArrayList<>();

    /**
     * 原文中识别到的基础区块（信贷记录／公共记录／查询记录），由解析流水线灌入。
     *
     * <p>只用于判定「输入是否是一份征信报告」，与明细条数无关：结构齐全但全无数据
     * 仍属正常（解析出空数据），结构缺失才说明输入不对。</p>
     */
    private Set<String> sections = new LinkedHashSet<>();

    /**
     * 追加一条解析告警。
     *
     * @param msg 告警内容
     */
    public void warn(String msg) {
        warnings.add(msg);
    }
}
