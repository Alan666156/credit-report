package com.workbuddy.pbccrc.parser;

import com.workbuddy.pbccrc.engine.FieldRule;
import com.workbuddy.pbccrc.engine.RuleCacheHolder;
import com.workbuddy.pbccrc.engine.RegexEngine;
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
 * <p>按 span.h1 标题判断信用卡、购房贷款或其他贷款类型，逐条 li 套用配置正则抽取字段，
 * 回填信用卡与贷款明细，并额外生成一条原始信贷记录留原文。逾期分组由 li 的 group 判断。</p>
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
