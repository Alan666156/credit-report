package com.workbuddy.pbccrc.parser;

import lombok.Data;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.List;

/**
 * 报告 HTML 分块器。
 *
 * <p>用 Jsoup 将整份征信报告切分为报告头、信息概要表、信贷明细块、公共记录、
 * 查询记录与说明六个部分，封装为 {@link Parts} 供各子解析器消费。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
public final class BlockSplitter {

    private BlockSplitter() {
    }

    /**
     * 报告分块结果，包含六个可独立解析的部分。
     */
    @Data
    public static class Parts {
        private String headText;
        private Element summaryTable;
        private List<CreditBlock> creditBlocks = new ArrayList<>();
        private String publicText = "";
        private List<QueryBlock> queryBlocks = new ArrayList<>();
        private String descriptionText = "";
        private String creditIntroText = "";
        private String queryIntroText = "";
    }

    /**
     * 一个信贷明细块，对应一个 span.h1 标题下的 ol 列表。
     */
    @Data
    public static class CreditBlock {
        private String title;
        private List<LiItem> items = new ArrayList<>();
    }

    /**
     * 信贷明细中的单个 li 条目。
     */
    @Data
    public static class LiItem {
        private String text;
        private String group;

        /**
         * 构造方法。
         *
         * @param text  条目文本
         * @param group 条目所属分组（如逾期分组）
         */
        public LiItem(String text, String group) {
            this.text = text;
            this.group = group;
        }
    }

    /**
     * 一类查询记录块（机构查询或个人查询），含去重后的行数据。
     */
    @Data
    public static class QueryBlock {
        private String type;
        private List<String[]> rows = new ArrayList<>();
    }

    /**
     * 将报告 HTML 切分为六块。
     *
     * <p>依次识别概要表、各类引导文本、信贷 ol 列表、查询记录表格与说明 ol 列表；
     * 查询记录以前四列组合去重，避免重复行入库。</p>
     *
     * @param html 报告 HTML 原文
     * @return 分块结果
     */
    public static Parts split(String html) {
        Parts p = new Parts();
        Document doc = Jsoup.parse(html == null ? "" : html);

        // 报告头只取各区块引导文字之前的 strong.p，否则「这部分包含…」会被并进证件号码段
        StringBuilder head = new StringBuilder();
        for (Element el : doc.select("strong.p")) {
            String tx = HtmlUtil.clean(el.text());
            if (tx.contains("这部分包含") || tx.contains("系统中没有")) {
                break;
            }
            if (!tx.isEmpty()) {
                if (head.length() > 0) {
                    head.append(' ');
                }
                head.append(tx);
            }
        }
        p.setHeadText(head.toString());

        for (Element table : doc.select("table")) {
            String tx = table.text();
            if (tx.contains("账户数") && tx.contains("信用卡") && tx.contains("购房贷款")) {
                p.setSummaryTable(table);
            }
            if (HtmlUtil.contains(tx, "这部分包含您的信用卡")) {
                p.setCreditIntroText(HtmlUtil.clean(tx));
            }
            if (HtmlUtil.contains(tx, "最近2年被查询")) {
                p.setQueryIntroText(HtmlUtil.clean(tx));
            }
        }
        p.setPublicText(extractPublicText(doc));

        for (Element ol : doc.select("ol.olstyle")) {
            CreditBlock block = new CreditBlock();
            block.setTitle(findNearestTitle(ol));
            String group = "";
            for (Element child : ol.children()) {
                if ("span".equals(child.tagName()) && child.hasClass("spanem")) {
                    group = HtmlUtil.clean(child.text());
                } else if ("li".equals(child.tagName())) {
                    block.getItems().add(new LiItem(HtmlUtil.clean(child.text()), group));
                }
            }
            if (!block.getItems().isEmpty()) {
                p.getCreditBlocks().add(block);
            }
        }

        String currentType = null;
        java.util.Map<String, java.util.Set<String>> rowKeys = new java.util.LinkedHashMap<>();
        java.util.Map<String, QueryBlock> queryByType = new java.util.LinkedHashMap<>();
        for (Element tr : doc.select("tr")) {
            String trText = HtmlUtil.clean(tr.text());
            if (trText.contains("机构查询记录明细")) {
                currentType = "1";
            } else if (trText.contains("个人查询记录明细")) {
                currentType = "2";
            }
            if (currentType == null) {
                continue;
            }
            List<String> cells = new ArrayList<>();
            for (Element td : tr.select("td")) {
                cells.add(HtmlUtil.clean(td.text()));
            }
            if (cells.size() < 4 || !cells.get(0).matches("\\d+")) {
                continue;
            }
            String key = String.join("|", cells.subList(0, 4));
            java.util.Set<String> keys = rowKeys.computeIfAbsent(currentType, k -> new java.util.LinkedHashSet<>());
            if (!keys.add(key)) {
                continue;
            }
            QueryBlock qb = queryByType.computeIfAbsent(currentType, k -> {
                QueryBlock b = new QueryBlock();
                b.setType(k);
                return b;
            });
            qb.getRows().add(new String[]{cells.get(0), cells.get(1), cells.get(2), cells.get(3)});
        }
        p.getQueryBlocks().addAll(queryByType.values());

        Element descOl = null;
        for (Element el : doc.select(".h1")) {
            String tx = HtmlUtil.clean(el.text());
            if (tx.replace(" ", "").equals("说明")) {
                Element table = el.closest("table");
                Element sibling = table == null ? null : table.nextElementSibling();
                if (sibling != null && "ol".equals(sibling.tagName())) {
                    descOl = sibling;
                }
                break;
            }
        }
        if (descOl == null) {
            for (Element ol : doc.select("ol")) {
                if (!ol.hasClass("olstyle")) {
                    descOl = ol;
                    break;
                }
            }
        }
        if (descOl != null) {
            p.setDescriptionText(HtmlUtil.clean(descOl.text()));
        }
        return p;
    }

    /**
     * 向上寻找 ol 最近的上一个 h1 标题。真实报告中 h1 与 ol 之间常夹有 br 标签，
     * 只看紧邻的前一个元素会取不到标题，进而让整块明细因标题为 null 被丢弃。
     * 遇到另一个 ol 或 table 即停止，避免跨块误取上一块的标题。
     *
     * @param ol 信贷明细列表元素
     * @return 最近的 h1 标题文本，找不到返回 null
     */
    private static String findNearestTitle(Element ol) {
        Element sib = ol.previousElementSibling();
        while (sib != null) {
            if (sib.hasClass("h1")) {
                return HtmlUtil.clean(sib.text());
            }
            if ("ol".equals(sib.tagName()) || "table".equals(sib.tagName())) {
                break;
            }
            sib = sib.previousElementSibling();
        }
        return null;
    }

    /**
     * 提取公共记录文本：只认真「公共记录」区块，并把紧随其后的电信欠费明细表一并收进文本，
     * 供解析器按类别抽取。若区块内写的是「系统中没有」，原样返回，保持空公共记录的语义。
     *
     * <p>定位方式是本方法的关键：真实报告的整份正文被一个最外层 {@code <table>} 包着，
     * 它的 {@code text()} 里必然出现「公共记录」四个字。早期实现取「文档顺序第一个包含
     * 公共记录且不含『系统中没有』的 table」，于是只要报告里真的存在公共记录（外层表就不再
     * 含「系统中没有」），整份报告都会被当成公共记录——信贷明细被二次分类成公共记录，
     * 担保/代偿出现重复脏数据，{@code common_record_description} 也存进了全文。现在改为
     * <b>按 {@code .h1} 标题定位</b>，标题缺失时才退化为「候选表中文本最短者」。</p>
     *
     * @param doc 报告文档
     * @return 公共记录文本，报告未包含公共记录区块时返回空串
     */
    private static String extractPublicText(Document doc) {
        Element block = findByHeading(doc);
        if (block == null) {
            block = findSmallestCandidate(doc);
        }
        if (block == null) {
            return findNoRecordText(doc);
        }
        StringBuilder sb = new StringBuilder(HtmlUtil.clean(block.text()));
        Element sib = block.nextElementSibling();
        while (sib != null) {
            String tx = HtmlUtil.clean(sib.text());
            if (tx.contains("为他人担保") || tx.contains("查询记录") || tx.contains("公共记录")) {
                break;
            }
            if ("table".equals(sib.tagName()) && HtmlUtil.contains(tx, "电信")) {
                sb.append(' ').append(tx);
            }
            sib = sib.nextElementSibling();
        }
        return sb.toString();
    }

    /**
     * 按 {@code .h1} 标题文本定位「公共记录」区块所在的最内层 table。
     *
     * <p>标题单元格自身带 {@code class="h1"}，用 {@link Element#closest(String)} 回溯到
     * 最近的 table，即可跳过包裹全文的外层表。</p>
     *
     * @param doc 报告文档
     * @return 公共记录区块的 table，未找到返回 null
     */
    private static Element findByHeading(Document doc) {
        for (Element el : doc.select(".h1")) {
            if ("公共记录".equals(HtmlUtil.clean(el.text()).replace(" ", ""))) {
                Element table = el.closest("table");
                if (table != null) {
                    return table;
                }
            }
        }
        return null;
    }

    /**
     * 标题缺失时的兜底：在包含「公共记录」的候选表中取文本最短的一个。
     *
     * <p>包裹全文的外层表文本长度是真正区块的几十倍，取最短者即可排除它；这比「文档顺序第一个」
     * 稳健，也不依赖报告里是否出现「系统中没有」。</p>
     *
     * @param doc 报告文档
     * @return 候选 table，无候选返回 null
     */
    private static Element findSmallestCandidate(Document doc) {
        Element smallest = null;
        for (Element table : doc.select("table")) {
            if (!HtmlUtil.contains(table.text(), "公共记录")) {
                continue;
            }
            if (smallest == null || table.text().length() < smallest.text().length()) {
                smallest = table;
            }
        }
        return smallest;
    }

    /**
     * 报告完全没有公共记录标题时，退回旧语义：返回「系统中没有」原文留痕。
     *
     * @param doc 报告文档
     * @return 「系统中没有」原文，未找到返回空串
     */
    private static String findNoRecordText(Document doc) {
        for (Element table : doc.select("table")) {
            if (HtmlUtil.contains(table.text(), "系统中没有")) {
                return HtmlUtil.clean(table.text());
            }
        }
        return "";
    }
}
