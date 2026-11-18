package com.workbuddy.pbccrc.parser;

import com.workbuddy.pbccrc.engine.FieldRule;
import com.workbuddy.pbccrc.engine.RuleCacheHolder;
import com.workbuddy.pbccrc.engine.RegexEngine;
import com.workbuddy.pbccrc.model.entity.AssetDisposal;
import com.workbuddy.pbccrc.model.entity.Compensate;
import com.workbuddy.pbccrc.model.entity.CreditCardRecord;
import com.workbuddy.pbccrc.model.entity.CreditRecord;
import com.workbuddy.pbccrc.model.entity.Guarantee;
import com.workbuddy.pbccrc.model.entity.LoanRecord;
import com.workbuddy.pbccrc.model.enums.ParseType;
import com.workbuddy.pbccrc.util.BeanFillUtil;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 信贷明细解析器。
 *
 * <p>按 span.h1 标题分流：信用卡、购房贷款、其他贷款逐条 li 套用配置正则抽取字段，回填信用卡与
 * 贷款明细，并额外生成一条原始信贷记录留原文；「为他人担保」「资产处置」「保证人代偿」三类
 * 各自归入担保、资产处置、代偿表。逾期分组由 li 的 group 判断。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
@Component
public class CreditDetailParser {

    private final RuleCacheHolder rules;

    /**
     * 构造方法，由 Spring 注入规则缓存。
     *
     * @param rules 规则缓存持有者
     */
    public CreditDetailParser(RuleCacheHolder rules) {
        this.rules = rules;
    }

    /**
     * 解析全部信贷明细块。
     *
     * @param ctx   解析上下文
     * @param parts 报告分块结果
     */
    public void parse(ParseContext ctx, BlockSplitter.Parts parts) {
        int cardNum = 0;
        int loanNum = 0;
        for (BlockSplitter.CreditBlock block : parts.getCreditBlocks()) {
            String title = block.getTitle();
            if (title == null || title.isBlank()) {
                ctx.warn("信贷块标题为空，已跳过，条目数=" + block.getItems().size());
                continue;
            }
            if (title.contains("为他人担保")) {
                parseGuarantees(ctx, block);
                continue;
            }
            if (title.contains("资产处置")) {
                parseAssetDisposals(ctx, block);
                continue;
            }
            if (title.contains("代偿")) {
                parseCompensates(ctx, block);
                continue;
            }
            boolean card = title.contains("信用卡");
            boolean house = title.contains("购房贷款");
            boolean other = title.contains("其他贷款");
            if (!card && !house && !other) {
                // 标题关键词未命中时不能静默丢弃整块，否则模板改名会让整组明细消失且无人察觉
                ctx.warn("信贷块标题未识别，已跳过: " + title + "，条目数=" + block.getItems().size());
                continue;
            }
            List<FieldRule> fieldRules = rules.of(card ? ParseType.CREDIT_CARD : ParseType.LOAN);
            int seq = 0;
            for (BlockSplitter.LiItem item : block.getItems()) {
                seq++;
                try {
                    String text = item.getText();
                    boolean overdue = item.getGroup().contains("逾期") && !item.getGroup().contains("从未");
                    if (card) {
                        cardNum++;
                        CreditCardRecord rec = new CreditCardRecord();
                        rec.setReportId(ctx.getReportId());
                        rec.setRecordNum(cardNum);
                        rec.setContent(text);
                        rec.setBad('1');
                        rec.setActive('0');
                        rec.setCloseAccount('1');
                        rec.setType(overdue ? '0' : '1');
                        fill(rec, text, fieldRules, ctx);
                        ctx.getCreditCards().add(rec);
                    } else {
                        loanNum++;
                        LoanRecord rec = new LoanRecord();
                        rec.setReportId(ctx.getReportId());
                        rec.setRecordNum(loanNum);
                        rec.setContent(text);
                        rec.setLoanType(house ? "购房贷款" : "其他贷款");
                        rec.setSettlement('1');
                        rec.setBad('1');
                        rec.setType(overdue ? '0' : '1');
                        fill(rec, text, fieldRules, ctx);
                        ctx.getLoans().add(rec);
                    }
                    CreditRecord raw = new CreditRecord();
                    raw.setReportId(ctx.getReportId());
                    raw.setRecordNum(seq);
                    raw.setContent(text);
                    raw.setType(card ? (overdue ? "1" : "2")
                            : (house ? (overdue ? "11" : "12") : (overdue ? "21" : "22")));
                    ctx.getRawCreditRecords().add(raw);
                } catch (RuntimeException e) {
                    ctx.warn("信贷明细第 " + seq + " 条解析失败: " + e.getMessage());
                }
            }
        }
    }

    /**
     * 将「资产处置信息」块的每条 li 解析为资产处置明细。
     *
     * <p>该块属于信贷记录区（不在公共记录区），早先只识别信用卡/贷款/担保四类标题，
     * 于是整块被当成「标题未识别」跳过——真实记录进不了 {@code t_pbccrc_asset_disposal}。
     * 这里按 {@link ParseType#ASSET_DISPOSAL} 的配置规则抽取字段并回填。</p>
     *
     * @param ctx   解析上下文
     * @param block 资产处置信息块
     */
    private void parseAssetDisposals(ParseContext ctx, BlockSplitter.CreditBlock block) {
        List<FieldRule> fieldRules = rules.of(ParseType.ASSET_DISPOSAL);
        int seq = 0;
        for (BlockSplitter.LiItem item : block.getItems()) {
            seq++;
            try {
                String text = item.getText();
                AssetDisposal e = new AssetDisposal();
                e.setReportId(ctx.getReportId());
                e.setRecordNum(seq);
                e.setContent(text);
                BeanFillUtil.fill(e, RegexEngine.extract(text, fieldRules));
                ctx.getAssetDisposals().add(e);
            } catch (RuntimeException ex) {
                ctx.warn("资产处置第 " + seq + " 条解析失败: " + ex.getMessage());
            }
        }
    }

    /**
     * 将「保证人代偿信息」块的每条 li 解析为代偿明细。
     *
     * <p>与资产处置同理：该块此前被标题白名单漏掉而整块丢弃。按
     * {@link ParseType#COMPENSATE} 的配置规则抽取字段并回填。</p>
     *
     * @param ctx   解析上下文
     * @param block 保证人代偿信息块
     */
    private void parseCompensates(ParseContext ctx, BlockSplitter.CreditBlock block) {
        List<FieldRule> fieldRules = rules.of(ParseType.COMPENSATE);
        int seq = 0;
        for (BlockSplitter.LiItem item : block.getItems()) {
            seq++;
            try {
                String text = item.getText();
                Compensate e = new Compensate();
                e.setReportId(ctx.getReportId());
                e.setRecordNum(seq);
                e.setContent(text);
                BeanFillUtil.fill(e, RegexEngine.extract(text, fieldRules));
                ctx.getCompensates().add(e);
            } catch (RuntimeException ex) {
                ctx.warn("保证人代偿第 " + seq + " 条解析失败: " + ex.getMessage());
            }
        }
    }

    /**
     * 将「为他人担保」块的每条 li 按担保规则解析为担保明细，避免被当成信贷块静默跳过。
     *
     * @param ctx   解析上下文
     * @param block 为他人担保块
     */
    private void parseGuarantees(ParseContext ctx, BlockSplitter.CreditBlock block) {
        List<FieldRule> fieldRules = rules.of(ParseType.GUARANTEE);
        int seq = 0;
        for (BlockSplitter.LiItem item : block.getItems()) {
            seq++;
            try {
                String text = item.getText();
                Guarantee e = new Guarantee();
                e.setReportId(ctx.getReportId());
                e.setRecordNum(seq);
                e.setContent(text);
                BeanFillUtil.fill(e, RegexEngine.extract(text, fieldRules));
                if (e.getType() == null || e.getType().isBlank()) {
                    e.setType(ParseType.GUARANTEE.getDesc());
                }
                ctx.getGuarantees().add(e);
            } catch (RuntimeException ex) {
                ctx.warn("为他人担保第 " + seq + " 条解析失败: " + ex.getMessage());
            }
        }
    }

    /**
     * 用配置正则抽取字段并回填实体；若抽到 BAD 字段则覆盖标志位。
     *
     * @param bean       待回填实体
     * @param text       条目文本
     * @param fieldRules 抽取规则列表
     * @param ctx        解析上下文
     */
    private void fill(Object bean, String text, List<FieldRule> fieldRules, ParseContext ctx) {
        Map<String, Object> values = RegexEngine.extract(text, fieldRules);
        BeanFillUtil.fill(bean, values);
        if (values.containsKey("BAD")) {
            BeanFillUtil.fill(bean, Map.of("BAD", "0"));
        }
    }
}
