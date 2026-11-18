package com.workbuddy.pbccrc.parser;

import com.workbuddy.pbccrc.engine.FieldRule;
import com.workbuddy.pbccrc.engine.RegexEngine;
import com.workbuddy.pbccrc.engine.RuleCacheHolder;
import com.workbuddy.pbccrc.model.entity.*;
import com.workbuddy.pbccrc.model.enums.ParseType;
import com.workbuddy.pbccrc.util.BeanFillUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 公共记录解析器。
 *
 * <p>按日期切分公共记录文本：资产处置、代偿、担保按配置规则抽取字段入库并回填计数，
 * 行政处罚、民事判决、强制执行、欠税、电信欠费等类别先保留原文，待样本细化。
 * 单条解析失败只记录告警。</p>
 *
 * <p>类别判定是关键词 if/else 链，命中不到的段落此前会被静默丢弃。现改为逐条告警并输出
 * 原文摘要：这些段落仍完整保留在 {@code common_record_detail} 原始文本中，日志只用于收集
 * 样本、反推缺失的类别与规则。待规则流程设计完成后，未识别段落将改为落独立的原文留痕表，
 * 不再只靠日志追溯。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
@Slf4j
@Component
public class PublicRecordParser {

    private static final String DATE_SPLIT = "(?=\\d{4}年\\d{1,2}月\\d{1,2}日|电信运营商：)";

    /** 未识别类别的原文摘要长度上限，避免超长段落刷满日志。 */
    private static final int SNIPPET_LENGTH = 200;

    private final RuleCacheHolder rules;

    /**
     * 构造方法，由 Spring 注入规则缓存。
     *
     * @param rules 规则缓存持有者
     */
    public PublicRecordParser(RuleCacheHolder rules) {
        this.rules = rules;
    }

    /**
     * 解析公共记录并回填主表描述与各明细列表。
     *
     * @param ctx   解析上下文
     * @param parts 报告分块结果
     */
    public void parse(ParseContext ctx, BlockSplitter.Parts parts) {
        String text = parts.getPublicText();
        ctx.getReport().setCommonRecordDescription(text);
        ctx.getReport().setCommonRecordDetail(text);
        if (text != null && !text.isBlank() && !text.contains("系统中没有")) {
            int seq = 0;
            int unmatched = 0;
            for (String seg : Arrays.asList(text.split(DATE_SPLIT))) {
                String s = seg.replaceAll("^[，。；;\\s]+", "").trim();
                // 区块标题与引导文字不是记录，防止其包含的「行政处罚」等关键词被误归类
                if (s.isEmpty() || s.equals("公共记录") || s.contains("这部分包含")) {
                    continue;
                }
                seq++;
                try {
                    if (!classify(ctx, s, seq)) {
                        unmatched++;
                        // 未识别段落仍留在 common_record_detail 原文里，这里只补可观测性：
                        // 输出序号与摘要，便于按日志回收样本、反推缺失的类别规则
                        log.warn("report {} 公共记录第 {} 条未匹配任何类别，仅保留原文（摘要: {}）",
                                ctx.getReportId(), seq, abbreviate(s));
                    }
                } catch (RuntimeException e) {
                    ctx.warn("公共记录第 " + seq + " 条解析失败: " + e.getMessage());
                }
            }
            if (unmatched > 0) {
                // 汇总一条，便于按 reportId 聚合统计「规则覆盖率」，也方便日志检索
                log.warn("report {} 公共记录共 {} 条未识别类别，待补类别规则", ctx.getReportId(), unmatched);
            }
        }
        // 回填资产处置与代偿的记录条数：条数以明细列表为准，故必须放在早退分支之外，
        // 否则「没有公共记录」的报告即使信贷区有资产处置，asset_total 也会是 null
        ctx.getReport().setAssetTotal(ctx.getAssetDisposals().size());
        ctx.getReport().setRepaymentTotal(ctx.getCompensates().size());
    }

    /**
     * 按关键词判断单条公共记录类别并归入对应明细列表。
     *
     * @param ctx  解析上下文
     * @param text 单条记录文本
     * @param seq  记录序号
     * @return 命中已知类别返回 true；未命中任何类别返回 false（原文由调用方告警留痕）
     */
    private boolean classify(ParseContext ctx, String text, int seq) {
        if (text.contains("资产处置") || text.contains("接收债权")) {
            AssetDisposal e = new AssetDisposal();
            base(ctx, e, seq);
            e.setContent(text);
            fill(e, text, ParseType.ASSET_DISPOSAL);
            ctx.getAssetDisposals().add(e);
        } else if (text.contains("代偿")) {
            Compensate e = new Compensate();
            base(ctx, e, seq);
            e.setContent(text);
            fill(e, text, ParseType.COMPENSATE);
            ctx.getCompensates().add(e);
        } else if (text.contains("担保")) {
            ParseType type = text.contains("担保信用卡") ? ParseType.GUARANTEE_CARD
                    : text.contains("担保贷款") ? ParseType.GUARANTEE_LOAN : ParseType.GUARANTEE;
            Guarantee e = new Guarantee();
            base(ctx, e, seq);
            e.setContent(text);
            fill(e, text, type);
            e.setType(type.getDesc());
            ctx.getGuarantees().add(e);
        } else if (text.contains("行政处罚")) {
            AdminPunishment e = new AdminPunishment();
            e.setReportId(ctx.getReportId());
            e.setContent(text);
            ctx.getAdminPunishments().add(e);
        } else if (text.contains("民事判决")) {
            CcivilDecision e = new CcivilDecision();
            e.setReportId(ctx.getReportId());
            e.setContent(text);
            ctx.getCivilDecisions().add(e);
        } else if (text.contains("强制执行")) {
            CourtDecision e = new CourtDecision();
            e.setReportId(ctx.getReportId());
            e.setContent(text);
            ctx.getCourtDecisions().add(e);
        } else if (text.contains("欠税")) {
            TaxArrears e = new TaxArrears();
            e.setReportId(ctx.getReportId());
            e.setContent(text);
            ctx.getTaxArrears().add(e);
        } else if (text.contains("电信") || text.contains("欠费")) {
            TelecomOwe e = new TelecomOwe();
            e.setReportId(ctx.getReportId());
            e.setContent(text);
            ctx.getTelecomOwes().add(e);
        } else {
            return false;
        }
        return true;
    }

    /**
     * 截取原文摘要，供日志采样使用。
     *
     * @param text 原文
     * @return 不超过 {@value #SNIPPET_LENGTH} 字符的摘要，超长以省略号结尾
     */
    private static String abbreviate(String text) {
        return text.length() <= SNIPPET_LENGTH ? text : text.substring(0, SNIPPET_LENGTH) + "...";
    }

    /**
     * 回填记录的公共基础字段（报告主键与记录序号）。
     *
     * @param ctx  解析上下文
     * @param bean 待回填实体
     * @param seq  记录序号
     */
    private void base(ParseContext ctx, Object bean, int seq) {
        BeanFillUtil.fill(bean, Map.of("REPORT_ID", ctx.getReportId(), "RECORD_NUM", seq));
    }

    /**
     * 按指定类型的配置规则抽取字段并回填实体。
     *
     * @param bean 待回填实体
     * @param text 记录文本
     * @param type 解析类型
     */
    private void fill(Object bean, String text, ParseType type) {
        List<FieldRule> fieldRules = rules.of(type);
        Map<String, Object> values = RegexEngine.extract(text, fieldRules);
        BeanFillUtil.fill(bean, values);
    }
}
