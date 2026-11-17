package com.workbuddy.pbccrc.service;

import com.workbuddy.pbccrc.common.BizException;
import com.workbuddy.pbccrc.common.PageResult;
import com.workbuddy.pbccrc.model.dto.ReportDetailVO;
import com.workbuddy.pbccrc.model.dto.ReportQueryRequest;
import com.workbuddy.pbccrc.model.dto.ReportVO;

/**
 * 征信报告后台查询服务。
 *
 * <p>提供按证件号加姓名的分页列表查询、查询留痕、按主键的状态查询以及主表加
 * 各类明细的全量详情查询。查询均未命中时抛出「未找到」业务异常。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
public interface ReportQueryService {

    /**
     * 按证件号加姓名分页查询报告列表。
     *
     * @param req 查询请求，含证件号、姓名及分页参数
     * @return 报告视图分页结果
     */
    PageResult<ReportVO> query(ReportQueryRequest req);

    /**
     * 记录查询流水：仅当请求带有操作人时留痕。
     *
     * @param req 查询请求，含操作人、证件号、姓名
     */
    void recordQuery(ReportQueryRequest req);

    /**
     * 按报告主键查询状态与汇总信息。
     *
     * @param reportId 报告主键
     * @return 报告视图
     * @throws BizException 报告不存在时抛出「未找到」
     */
    ReportVO status(Integer reportId);

    /**
     * 按报告主键查询全量详情，包括主表与全部明细类别。
     *
     * @param reportId 报告主键
     * @return 报告详情视图，含各类明细列表
     * @throws BizException 报告不存在时抛出「未找到」
     */
    ReportDetailVO detail(Integer reportId);
}
