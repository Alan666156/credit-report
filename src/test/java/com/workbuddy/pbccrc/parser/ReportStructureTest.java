package com.workbuddy.pbccrc.parser;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基础区块识别：判定「输入是不是一份征信报告」的唯一依据。
 *
 * <p>判据是结构，不是数据。真实报告即使三类记录全为空，版式上仍保留「信贷记录」
 * 「公共记录」「查询记录」三个 {@code .h1} 标题；三块全无说明输入不是征信报告，
 * 或 HTML 已被截断——这两种情况都不该产出 SUCCESS。</p>
 *
 * <p>反过来也要守住：结构齐全但一条明细都解析不出是合法的空报告，不能因此判失败。
 * {@code ParseServiceImpl.ensureReportStructure} 依赖这里的识别结果。</p>
 *
 * @author Alan
 * @date 2026-11-18
 */
class ReportStructureTest {

    /** 两份真实样例都必须识别出全部三个基础区块。 */
    @Test
    void realReportsExposeAllRequiredSections() throws Exception {
        for (String file : List.of("/sample_report.html", "/report_all.html")) {
            BlockSplitter.Parts parts = BlockSplitter.split(read(file));
            assertEquals(new HashSet<>(BlockSplitter.REQUIRED_SECTIONS), parts.getSections(),
                    file + " 的基础区块识别不完整");
        }
    }

    /** 非征信报告：一个区块都没有。 */
    @Test
    void nonReportHtmlHasNoSection() {
        BlockSplitter.Parts parts = BlockSplitter.split(
                "<html><body><p>这不是一份征信报告</p></body></html>");
        assertTrue(parts.getSections().isEmpty(), "非报告 HTML 不应识别出任何基础区块");
    }

    /** 正文里出现同名字样不算结构，必须是 .h1 标题。 */
    @Test
    void sectionKeywordInBodyIsNotStructure() {
        BlockSplitter.Parts parts = BlockSplitter.split(
                "<html><body><p>本报告包含信贷记录、公共记录与查询记录明细</p></body></html>");
        assertTrue(parts.getSections().isEmpty(), "只有正文提及、没有 .h1 标题时不应算作结构");
    }

    /** 结构齐全但内容全空：区块照常识别，明细为空——这是合法的空报告，必须判成功。 */
    @Test
    void emptyButStructuredReportStillHasSections() {
        BlockSplitter.Parts parts = BlockSplitter.split("""
                <html><body>
                  <span class="h1">信贷记录</span>
                  <span class="h1">公共记录</span>
                  <span class="h1">查询记录</span>
                </body></html>
                """);
        assertEquals(new HashSet<>(BlockSplitter.REQUIRED_SECTIONS), parts.getSections(),
                "空数据的报告仍应保留三个基础区块");
        assertTrue(parts.getCreditBlocks().isEmpty(), "无 ol 列表时不应产出信贷块");
        assertTrue(parts.getQueryBlocks().isEmpty(), "无表格时不应产出查询块");
    }

    /** 截断的报告：只认到一部分，缺失项可被上层准确报出。 */
    @Test
    void truncatedReportReportsMissingSections() {
        BlockSplitter.Parts parts = BlockSplitter.split("""
                <html><body>
                  <span class="h1">信贷记录</span>
                  <span class="h1">公共记录</span>
                </body></html>
                """);
        assertEquals(List.of(BlockSplitter.SECTION_QUERY),
                BlockSplitter.REQUIRED_SECTIONS.stream()
                        .filter(s -> !parts.getSections().contains(s))
                        .toList(),
                "缺少的应恰好是查询记录");
    }

    private static String read(String resource) throws Exception {
        try (InputStream in = ReportStructureTest.class.getResourceAsStream(resource)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
