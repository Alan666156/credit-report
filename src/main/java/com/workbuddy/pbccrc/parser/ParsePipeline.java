package com.workbuddy.pbccrc.parser;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 解析流水线，聚合六个子解析器。
 *
 * <p>先由 {@link BlockSplitter} 切块，再依次调用报告头、信息概要、信贷明细、
 * 公共记录、查询记录、说明六个解析器。单个区块失败只记录告警并继续其余区块
 * （部分成功），所有告警汇总写入主表 error_msg 留痕，不阻断整份报告。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
@Slf4j
@Component
public class ParsePipeline {

    private final CreditDetailParser creditDetailParser;
    private final PublicRecordParser publicRecordParser;
    private final QueryRecordParser queryRecordParser;

    /**
     * 构造方法，由 Spring 注入各子解析器。
     *
     * @param creditDetailParser 信贷明细解析器
     * @param publicRecordParser 公共记录解析器
     * @param queryRecordParser  查询记录解析器
     */
    public ParsePipeline(CreditDetailParser creditDetailParser,
                         PublicRecordParser publicRecordParser,
                         QueryRecordParser queryRecordParser) {
        this.creditDetailParser = creditDetailParser;
        this.publicRecordParser = publicRecordParser;
        this.queryRecordParser = queryRecordParser;
    }

    /**
     * 对一份报告执行完整解析。
     *
     * <p>六个区块逐一通过 {@link #safe} 包装调用，任一区块抛异常只记录告警；
     * 解析结束后若有告警则汇总写入主表 error_msg。</p>
     *
     * @param ctx 解析上下文
     */
    public void parse(ParseContext ctx) {
        parse(ctx, null);
    }

    /**
     * 对一份报告执行完整解析，并在每个阶段推进后回调进度钩子。
     *
     * <p>评审 2.3：进度钩子用于刷新「进度时间戳」而非定时心跳，只有阶段真正推进时才更新，
     * 使看门狗能识别解析卡死。传 null 表示无需进度回调，兼容原有调用与测试。</p>
     *
     * @param ctx        解析上下文
     * @param onProgress 每阶段推进后的回调，可为 null
     */
    public void parse(ParseContext ctx, Runnable onProgress) {
        BlockSplitter.Parts parts = BlockSplitter.split(ctx.getHtml());
        safe(ctx, "报告头", () -> ReportHeadParser.parse(ctx, parts));
        advance(onProgress);
        safe(ctx, "信息概要", () -> CreditSummaryParser.parse(ctx, parts));
        advance(onProgress);
        safe(ctx, "信贷明细", () -> creditDetailParser.parse(ctx, parts));
        advance(onProgress);
        safe(ctx, "公共记录", () -> publicRecordParser.parse(ctx, parts));
        advance(onProgress);
        safe(ctx, "查询记录", () -> queryRecordParser.parse(ctx, parts));
        advance(onProgress);
        safe(ctx, "说明", () -> DescriptionParser.parse(ctx, parts));
        advance(onProgress);

        if (!ctx.getWarnings().isEmpty()) {
            String msg = String.join("; ", ctx.getWarnings());
            ctx.getReport().setErrorMsg(msg);
            log.warn("report {} 部分区块解析告警: {}", ctx.getReportId(), msg);
        }
    }

    /**
     * 触发一次进度回调。
     *
     * @param onProgress 进度回调，为 null 时忽略
     */
    private void advance(Runnable onProgress) {
        if (onProgress != null) {
            onProgress.run();
        }
    }

    /**
     * 安全执行一个解析区块，异常只记录告警不向外抛。
     *
     * @param ctx    解析上下文
     * @param name   区块名称，用于告警定位
     * @param action 区块解析动作
     */
    private void safe(ParseContext ctx, String name, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.error("report {} [{}] 区块解析失败", ctx.getReportId(), name, e);
            ctx.warn(name + " 区块解析失败: " + e.getMessage());
        }
    }
}
