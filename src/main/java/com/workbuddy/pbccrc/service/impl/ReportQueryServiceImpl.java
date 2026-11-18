package com.workbuddy.pbccrc.service.impl;

import com.workbuddy.pbccrc.common.BizException;
import com.workbuddy.pbccrc.common.OperatorContext;
import com.workbuddy.pbccrc.common.PageResult;
import com.workbuddy.pbccrc.common.ResultCode;
import com.workbuddy.pbccrc.dao.CustomerHistoryMapper;
import com.workbuddy.pbccrc.dao.DetailQueryMapper;
import com.workbuddy.pbccrc.dao.ReportContentMapper;
import com.workbuddy.pbccrc.dao.ReportMapper;
import com.workbuddy.pbccrc.model.dto.ReportDetailVO;
import com.workbuddy.pbccrc.model.dto.ReportPageRequest;
import com.workbuddy.pbccrc.model.dto.ReportQueryRequest;
import com.workbuddy.pbccrc.model.dto.ReportVO;
import com.workbuddy.pbccrc.model.entity.CustomerHistory;
import com.workbuddy.pbccrc.model.entity.Report;
import com.workbuddy.pbccrc.model.entity.ReportContent;
import com.workbuddy.pbccrc.service.ReportQueryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 征信报告后台查询服务实现。
 *
 * <p>提供按证件号加姓名的分页列表查询、查询留痕、按主键的状态查询以及主表加
 * 各类明细的全量详情查询。查询均未命中时抛出「未找到」业务异常。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
@Service
public class ReportQueryServiceImpl implements ReportQueryService {

    private final ReportMapper reportMapper;
    private final DetailQueryMapper detailQueryMapper;
    private final CustomerHistoryMapper customerHistoryMapper;
    private final ReportContentMapper reportContentMapper;

    /**
     * 构造方法，由 Spring 注入依赖。
     *
     * @param reportMapper          报告主表数据访问对象
     * @param detailQueryMapper     明细查询数据访问对象
     * @param customerHistoryMapper 客户查询历史数据访问对象
     * @param reportContentMapper   报告原文数据访问对象
     */
    public ReportQueryServiceImpl(ReportMapper reportMapper, DetailQueryMapper detailQueryMapper,
                                  CustomerHistoryMapper customerHistoryMapper,
                                  ReportContentMapper reportContentMapper) {
        this.reportMapper = reportMapper;
        this.detailQueryMapper = detailQueryMapper;
        this.customerHistoryMapper = customerHistoryMapper;
        this.reportContentMapper = reportContentMapper;
    }

    /**
     * 按证件号加姓名分页查询报告列表。
     *
     * <p>页码与每页条数做边界收敛：页码至少为 1，每页条数限制在 1 到 100 之间。</p>
     *
     * @param req 查询请求，含证件号、姓名及分页参数
     * @return 报告视图分页结果
     */
    @Override
    @Transactional(readOnly = true)
    public PageResult<ReportVO> query(ReportQueryRequest req) {
        int page = req.getPageNum() == null ? 1 : Math.max(req.getPageNum(), 1);
        int size = req.getPageSize() == null ? 10 : Math.min(Math.max(req.getPageSize(), 1), 100);
        int offset = (page - 1) * size;
        long total = reportMapper.countByCustomer(req.getCustomerIdCard(), req.getCustomerName());
        List<ReportVO> list = reportMapper.findByCustomer(req.getCustomerIdCard(),
                req.getCustomerName(), offset, size);
        return PageResult.of(total, page, size, list);
    }

    /**
     * 后台管理：按可选条件分页浏览全部报告。
     *
     * @param req 查询请求，含用户名、姓名、创建时间区间及分页参数
     * @return 报告视图分页结果
     */
    @Override
    @Transactional(readOnly = true)
    public PageResult<ReportVO> page(ReportPageRequest req) {
        int page = req.getPageNum() == null ? 1 : Math.max(req.getPageNum(), 1);
        int size = req.getPageSize() == null ? 10 : Math.min(Math.max(req.getPageSize(), 1), 100);
        int offset = (page - 1) * size;
        long total = reportMapper.countByCondition(req.getCustomerName(), req.getName(),
                req.getCreateTimeStart(), req.getCreateTimeEnd());
        List<ReportVO> list = reportMapper.findByCondition(req.getCustomerName(), req.getName(),
                req.getCreateTimeStart(), req.getCreateTimeEnd(), offset, size);
        return PageResult.of(total, page, size, list);
    }

    /**
     * 按报告主键查询原始 HTML 报告正文。
     *
     * @param reportId 报告主键
     * @return 报告 HTML 原文
     * @throws BizException 报告原文不存在时抛出「未找到」
     */
    @Override
    @Transactional(readOnly = true)
    public String html(Integer reportId) {
        ReportContent content = reportContentMapper.findByReportId(reportId);
        if (content == null || content.getContent() == null) {
            throw new BizException(ResultCode.NOT_FOUND);
        }
        return content.getContent();
    }

    /**
     * 记录查询流水：仅当请求带有操作人时留痕。
     *
     * @param req 查询请求，含操作人、证件号、姓名
     */
    @Override
    @Transactional
    public void recordQuery(ReportQueryRequest req) {
        // 操作人优先取鉴权上下文中的可信身份，避免客户端自报工号（评审 2.5）
        String operator = OperatorContext.getOrDefault(req.getOperator());
        if (operator == null || operator.isBlank()) {
            return;
        }
        CustomerHistory h = new CustomerHistory();
        h.setQueryDate(LocalDateTime.now());
        h.setCustomerIdCard(req.getCustomerIdCard());
        h.setCustomerName(req.getCustomerName());
        h.setOperator(operator);
        h.setReportState("QUERY");
        h.setCreateTime(LocalDateTime.now());
        h.setCreatorId(operator);
        customerHistoryMapper.insert(h);
    }

    /**
     * 按报告主键查询状态与汇总信息。
     *
     * @param reportId 报告主键
     * @return 报告视图
     * @throws BizException 报告不存在时抛出「未找到」
     */
    @Override
    @Transactional(readOnly = true)
    public ReportVO status(Integer reportId) {
        ReportVO vo = reportMapper.findVOById(reportId);
        if (vo == null) {
            throw new BizException(ResultCode.NOT_FOUND);
        }
        return vo;
    }

    /**
     * 按报告主键查询全量详情，包括主表与全部明细类别。
     *
     * @param reportId 报告主键
     * @return 报告详情视图，含各类明细列表
     * @throws BizException 报告不存在时抛出「未找到」
     */
    @Override
    @Transactional(readOnly = true)
    public ReportDetailVO detail(Integer reportId) {
        Report report = reportMapper.findById(reportId);
        if (report == null) {
            throw new BizException(ResultCode.NOT_FOUND);
        }
        ReportDetailVO vo = new ReportDetailVO();
        vo.setReport(report);
        vo.setCreditCards(detailQueryMapper.creditCards(reportId));
        vo.setLoans(detailQueryMapper.loans(reportId));
        vo.setQueryRecords(detailQueryMapper.queryRecords(reportId));
        vo.setAssetDisposals(detailQueryMapper.assetDisposals(reportId));
        vo.setCompensates(detailQueryMapper.compensates(reportId));
        vo.setGuarantees(detailQueryMapper.guarantees(reportId));
        vo.setRawCreditRecords(detailQueryMapper.rawCreditRecords(reportId));
        vo.setAdminPunishments(detailQueryMapper.adminPunishments(reportId));
        vo.setCivilDecisions(detailQueryMapper.civilDecisions(reportId));
        vo.setCourtDecisions(detailQueryMapper.courtDecisions(reportId));
        vo.setTaxArrears(detailQueryMapper.taxArrears(reportId));
        vo.setTelecomOwes(detailQueryMapper.telecomOwes(reportId));
        return vo;
    }
}
