package com.workbuddy.pbccrc.controller;

import com.workbuddy.pbccrc.common.PageResult;
import com.workbuddy.pbccrc.common.Result;
import com.workbuddy.pbccrc.config.PbccrcProperties;
import com.workbuddy.pbccrc.dao.ReportMapper;
import com.workbuddy.pbccrc.model.dto.ReportDetailVO;
import com.workbuddy.pbccrc.model.dto.ReportPageRequest;
import com.workbuddy.pbccrc.model.dto.ReportQueryRequest;
import com.workbuddy.pbccrc.model.dto.ReportVO;
import com.workbuddy.pbccrc.model.dto.SubmitRequest;
import com.workbuddy.pbccrc.service.ParseService;
import com.workbuddy.pbccrc.service.ReportQueryService;
import com.workbuddy.pbccrc.service.SubmitService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * 征信报告对外 REST 接口。
 *
 * <p>提供报告的提交、状态查询、后台按证件号加姓名查询、全量详情查询、手动重试
 * 以及死信清单查询。提交接口先同步落原文再异步解析，查询接口统一返回 {@link Result} 包装。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
@RestController
@RequestMapping("/api/pbccrc")
public class ReportController {

    private final SubmitService submitService;
    private final ParseService parseService;
    private final ReportQueryService queryService;
    private final ReportMapper reportMapper;
    private final PbccrcProperties properties;

    /**
     * 构造方法，由 Spring 注入各业务服务。
     *
     * @param submitService 报告提交服务
     * @param parseService  解析服务
     * @param queryService  报告查询服务
     * @param reportMapper  报告主表数据访问对象
     * @param properties    业务配置
     */
    public ReportController(SubmitService submitService, ParseService parseService,
                            ReportQueryService queryService, ReportMapper reportMapper,
                            PbccrcProperties properties) {
        this.submitService = submitService;
        this.parseService = parseService;
        this.queryService = queryService;
        this.reportMapper = reportMapper;
        this.properties = properties;
    }

    /**
     * 提交征信报告。
     *
     * <p>先同步存储原文并返回 reportId，再触发异步解析。</p>
     *
     * @param req 提交请求，含证件号、姓名、原文等
     * @return 包装后的报告主键 reportId
     */
    @PostMapping("/reports")
    public Result<Integer> submit(@Valid @RequestBody SubmitRequest req) {
        Integer reportId = submitService.submit(req);
        parseService.parseAsync(reportId);
        return Result.ok(reportId);
    }

    /**
     * 后台管理：按身份证号加姓名分页查询报告。
     *
     * @param req 查询请求，含证件号、姓名、分页参数及操作人
     * @return 包装后的分页报告列表
     */
    @PostMapping("/admin/reports/query")
    public Result<PageResult<ReportVO>> query(@Valid @RequestBody ReportQueryRequest req) {
        queryService.recordQuery(req);
        return Result.ok(queryService.query(req));
    }

    /**
     * 后台管理：按可选条件分页浏览全部报告列表。
     *
     * @param req 查询请求，含用户名、姓名、创建时间区间及分页参数
     * @return 包装后的分页报告列表
     */
    @PostMapping("/admin/reports/page")
    public Result<PageResult<ReportVO>> page(@RequestBody ReportPageRequest req) {
        return Result.ok(queryService.page(req));
    }

    /**
     * 后台管理：按报告主键查看原始 HTML 报告正文。
     *
     * @param id 报告主键
     * @return 包装后的报告 HTML 原文
     */
    @GetMapping("/admin/reports/{id}/html")
    public Result<String> html(@PathVariable Integer id) {
        return Result.ok(queryService.html(id));
    }

    /**
     * 按报告主键查询解析状态与汇总信息。
     *
     * @param id 报告主键
     * @return 包装后的报告视图
     */
    @GetMapping("/reports/{id}")
    public Result<ReportVO> status(@PathVariable Integer id) {
        return Result.ok(queryService.status(id));
    }

    /**
     * 后台管理：按报告主键查询全量详情（主表加各类明细）。
     *
     * @param id 报告主键
     * @return 包装后的报告详情视图
     */
    @GetMapping("/admin/reports/{id}/detail")
    public Result<ReportDetailVO> detail(@PathVariable Integer id) {
        return Result.ok(queryService.detail(id));
    }

    /**
     * 手动重试解析。
     *
     * <p>仅失败状态可重试：拒绝重置解析中的报告，避免与在跑线程并发改同一份明细
     * （评审 3.3）。卡死或解析中的报告交给看门狗与补偿任务处理。</p>
     *
     * @param id 报告主键
     * @return 包装结果，成功为空；状态不允许时为失败
     */
    @PostMapping("/reports/{id}/retry")
    public Result<Void> retry(@PathVariable Integer id) {
        if (reportMapper.resetFailedToPending(id) == 0) {
            return Result.fail(com.workbuddy.pbccrc.common.ResultCode.PARAM_ERROR, "仅解析失败的报告可手动重试");
        }
        parseService.parseAsync(id);
        return Result.ok();
    }

    /**
     * 查询死信报告清单。
     *
     * @return 包装后的死信报告列表
     */
    @GetMapping("/dead-letters")
    public Result<?> deadLetters() {
        return Result.ok(reportMapper.findDeadLetters(properties.getParse().getMaxRetry()));
    }
}
