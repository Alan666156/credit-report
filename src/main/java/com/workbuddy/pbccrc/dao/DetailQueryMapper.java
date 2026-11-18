package com.workbuddy.pbccrc.dao;

import com.workbuddy.pbccrc.model.entity.*;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 按报告主键读取各明细表的数据访问接口。
 *
 * <p>供后台详情接口聚合各类明细，所有查询均按 {@code report_id} 过滤并按记录序号或主键排序。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
public interface DetailQueryMapper {

    /**
     * 查询信用卡记录。
     *
     * @param reportId 报告主键
     * @return 信用卡记录列表
     */
    List<CreditCardRecord> creditCards(@Param("reportId") Integer reportId);

    /**
     * 查询贷款记录。
     *
     * @param reportId 报告主键
     * @return 贷款记录列表
     */
    List<LoanRecord> loans(@Param("reportId") Integer reportId);

    /**
     * 查询查询记录。
     *
     * @param reportId 报告主键
     * @return 查询记录列表
     */
    List<QueryRecord> queryRecords(@Param("reportId") Integer reportId);

    /**
     * 查询资产处置记录。
     *
     * @param reportId 报告主键
     * @return 资产处置记录列表
     */
    List<AssetDisposal> assetDisposals(@Param("reportId") Integer reportId);

    /**
     * 查询代偿记录。
     *
     * @param reportId 报告主键
     * @return 代偿记录列表
     */
    List<Compensate> compensates(@Param("reportId") Integer reportId);

    /**
     * 查询担保记录。
     *
     * @param reportId 报告主键
     * @return 担保记录列表
     */
    List<Guarantee> guarantees(@Param("reportId") Integer reportId);

    /**
     * 查询原始信贷记录。
     *
     * @param reportId 报告主键
     * @return 原始信贷记录列表
     */
    List<CreditRecord> rawCreditRecords(@Param("reportId") Integer reportId);

    /**
     * 查询行政处罚记录。
     *
     * @param reportId 报告主键
     * @return 行政处罚记录列表
     */
    List<AdminPunishment> adminPunishments(@Param("reportId") Integer reportId);

    /**
     * 查询民事判决记录。
     *
     * @param reportId 报告主键
     * @return 民事判决记录列表
     */
    List<CcivilDecision> civilDecisions(@Param("reportId") Integer reportId);

    /**
     * 查询法院判决记录。
     *
     * @param reportId 报告主键
     * @return 法院判决记录列表
     */
    List<CourtDecision> courtDecisions(@Param("reportId") Integer reportId);

    /**
     * 查询欠税记录。
     *
     * @param reportId 报告主键
     * @return 欠税记录列表
     */
    List<TaxArrears> taxArrears(@Param("reportId") Integer reportId);

    /**
     * 查询电信欠费记录。
     *
     * @param reportId 报告主键
     * @return 电信欠费记录列表
     */
    List<TelecomOwe> telecomOwes(@Param("reportId") Integer reportId);
}
