package com.workbuddy.pbccrc.parser;

import com.workbuddy.pbccrc.engine.FieldRule;
import com.workbuddy.pbccrc.engine.RegexEngine;
import com.workbuddy.pbccrc.engine.RuleCacheHolder;
import com.workbuddy.pbccrc.model.entity.*;
import com.workbuddy.pbccrc.model.enums.ParseType;
import com.workbuddy.pbccrc.util.BeanFillUtil;
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
 * @author Alan
 * @date 2026-11-17
 */
@Component
public class PublicRecordParser {

    private static final String DATE_SPLIT = "(?=\\d{4}年\\d{1,2}月\\d{1,2}日|电信运营商：)";

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
            for (String seg : Arrays.asList(text.split(DATE_SPLIT))) {
                String s = seg.replaceAll("^[，。；;\\s]+", "").trim();
                // 区块标题与引导文字不是记录，防止其包含的「行政处罚」等关键词被误归类
                if (s.isEmpty() || s.equals("公共记录") || s.contains("这部分包含")) {
                    continue;
                }
                seq++;
                try {
                    classify(ctx, s, seq);
                } catch (RuntimeException e) {
                    ctx.warn("公共记录第 " + seq + " 条解析失败: " + e.getMessage());
                }
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
     */
    private void classify(ParseContext ctx, String text, int seq) {
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
        }
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
