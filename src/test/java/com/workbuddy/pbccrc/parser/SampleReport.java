package com.workbuddy.pbccrc.parser;

import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * 读取本地样例报告。
 *
 * <p>真实征信报告原文含个人信息，**不入版本库**（见 .gitignore），只在本地
 * {@code src/test/resources/} 放置。因此这些用例在拉取代码后可能因为没有样例而无法执行：
 * 此时用 {@link org.junit.jupiter.api.Assumptions} 跳过，而不是让构建变红——跳过是
 * 「本次没验证」，失败是「验证不通过」，两者必须区分开。</p>
 *
 * <p>代价要说清楚：依赖样例的用例在缺少文件的机器上（例如 CI）会被标记为 skipped，
 * 覆盖为零。想让 CI 也跑到这部分，需要另造一份**不含真实个人信息**的合成报告，
 * 不能靠把真实报告提交上来解决。</p>
 *
 * @author Alan
 * @date 2026-11-18
 */
final class SampleReport {

    /** 单份样例：一份完整报告，覆盖报告头、概要、信贷、查询、说明。 */
    static final String DEFAULT = "/sample_report.html";

    /** 第二份样例：公共记录与担保最全，用于容错用例。 */
    static final String FULL = "/report_all.html";

    private SampleReport() {
    }

    /**
     * 读取指定样例报告的文本内容。
     *
     * @param resource classpath 下的资源路径
     * @return 报告 HTML 文本
     * @throws org.opentest4j.TestAbortedException 资源不存在时抛出，使当前用例跳过
     */
    static String read(String resource) {
        try (InputStream in = SampleReport.class.getResourceAsStream(resource)) {
            Assumptions.assumeTrue(in != null,
                    "缺少本地样例 " + resource + "，跳过该用例（样例含个人信息，不入版本库）");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取样例失败: " + resource, e);
        }
    }
}
