package com.workbuddy.pbccrc.dao;

import com.workbuddy.pbccrc.util.SqlColumnUtil;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实体反射列 ↔ 数据库基线的一致性契约。
 *
 * <p>{@code DetailSqlProvider} 按实体字段名反射生成 INSERT 的列清单（驼峰转下划线），
 * 这是本服务唯一「隐式约定」的地方：实体加字段而不加列、列被改名、列被删，都不会在
 * 编译期报错，要等到某份报告真的带有该类明细时才在运行期炸。这里把
 * {@code db/schema.sql} 当作基线与实体逐字段比对，把隐式约定变成构建期断言。</p>
 *
 * <p>同时校验 {@code ReportMapper.xml} 引用的列都真实存在——历史上正是
 * {@code schema.sql} 缺 {@code status}/{@code retry_count}/{@code heartbeat_time}/
 * {@code content_hash} 四列，而 17 条语句全在用它们，按旧基线建库后全站 500。
 * 用例里的 H2 建表是各自手写的，抓不到这类漂移，所以单独设一道。</p>
 *
 * @author Alan
 * @date 2026-11-18
 */
class SchemaContractTest {

    /** 数据库基线脚本，相对项目根目录。 */
    private static final Path SCHEMA_PATH = Path.of("db", "schema.sql");

    /** 列定义行：两个空格 + 反引号列名；索引行以 PRIMARY KEY / KEY / UNIQUE KEY 开头，不会误匹配。 */
    private static final Pattern COLUMN_LINE = Pattern.compile("^\\s+`([a-z0-9_]+)`", Pattern.MULTILINE);

    /** 12 张明细表与实体的对应关系，须与 ParseResultWriterImpl 中的写入清单一致。 */
    private static final Map<String, Class<?>> DETAIL_TABLES = new LinkedHashMap<>();

    static {
        DETAIL_TABLES.put("t_pbccrc_credit_record", com.workbuddy.pbccrc.model.entity.CreditRecord.class);
        DETAIL_TABLES.put("t_pbccrc_credit_card_record", com.workbuddy.pbccrc.model.entity.CreditCardRecord.class);
        DETAIL_TABLES.put("t_pbccrc_loan_record", com.workbuddy.pbccrc.model.entity.LoanRecord.class);
        DETAIL_TABLES.put("t_pbccrc_query_record", com.workbuddy.pbccrc.model.entity.QueryRecord.class);
        DETAIL_TABLES.put("t_pbccrc_asset_disposal", com.workbuddy.pbccrc.model.entity.AssetDisposal.class);
        DETAIL_TABLES.put("t_pbccrc_compensate", com.workbuddy.pbccrc.model.entity.Compensate.class);
        DETAIL_TABLES.put("t_pbccrc_guarantee", com.workbuddy.pbccrc.model.entity.Guarantee.class);
        DETAIL_TABLES.put("t_pbccrc_admin_punishment", com.workbuddy.pbccrc.model.entity.AdminPunishment.class);
        DETAIL_TABLES.put("t_pbccrc_ccivil_decision", com.workbuddy.pbccrc.model.entity.CcivilDecision.class);
        DETAIL_TABLES.put("t_pbccrc_court_decision", com.workbuddy.pbccrc.model.entity.CourtDecision.class);
        DETAIL_TABLES.put("t_pbccrc_tax_arrears", com.workbuddy.pbccrc.model.entity.TaxArrears.class);
        DETAIL_TABLES.put("t_pbccrc_telecom_owe", com.workbuddy.pbccrc.model.entity.TelecomOwe.class);
    }

    /** 反射生成 INSERT 列清单时依赖的字段→列对应，必须全部命中基线。 */
    @Test
    void everyEntityFieldMapsToExistingColumn() throws IOException {
        String ddl = readSchema();
        for (Map.Entry<String, Class<?>> entry : DETAIL_TABLES.entrySet()) {
            String table = entry.getKey();
            Set<String> columns = columnsOf(ddl, table);
            assertFalse(columns.isEmpty(), "db/schema.sql 中找不到表或列: " + table);
            for (Field field : SqlColumnUtil.fields(entry.getValue())) {
                String column = SqlColumnUtil.toColumn(field.getName());
                assertTrue(columns.contains(column),
                        table + " 缺少列 " + column + "（实体 " + entry.getValue().getSimpleName()
                                + "." + field.getName() + "）。反射生成的 INSERT 会在运行期才报 Unknown column");
            }
        }
    }

    /** 主表的异步状态机四列与两个关键索引必须固化在基线里。 */
    @Test
    void reportTableCarriesAsyncStateColumnsAndIndexes() throws IOException {
        String ddl = readSchema();
        Set<String> columns = columnsOf(ddl, "t_pbccrc_report");
        assertFalse(columns.isEmpty(), "db/schema.sql 中找不到表 t_pbccrc_report");
        for (String column : new String[]{"status", "retry_count", "heartbeat_time", "content_hash"}) {
            assertTrue(columns.contains(column),
                    "t_pbccrc_report 缺少列 " + column + "：按本基线建库后 ReportMapper 全部语句会报 Unknown column");
        }
        assertTrue(ddl.contains("IDX_PBCCRC_STATUS"), "缺少 IDX_PBCCRC_STATUS，补偿扫描将退化为全表扫描");
        assertTrue(ddl.contains("IDX_PBCCRC_CUSTOMER_NAME_ID"),
                "缺少 IDX_PBCCRC_CUSTOMER_NAME_ID，findRecentForUpdate 的 FOR UPDATE 会退化为全表加锁");
    }

    /** ReportMapper.xml 中显式赋值的列都必须存在于基线，避免 SQL 与表结构各写各的。 */
    @Test
    void reportMapperXmlColumnsExistInSchema() throws IOException {
        Set<String> columns = columnsOf(readSchema(), "t_pbccrc_report");
        String xml = readClasspath("mapper/ReportMapper.xml");
        Set<String> referenced = new LinkedHashSet<>();
        Matcher m = Pattern.compile("^\\s{8,}([a-z][a-z0-9_]*)\\s*=", Pattern.MULTILINE).matcher(xml);
        while (m.find()) {
            referenced.add(m.group(1));
        }
        assertFalse(referenced.isEmpty(), "未能从 ReportMapper.xml 解析出任何赋值列，正则可能已失效");
        for (String column : referenced) {
            assertTrue(columns.contains(column),
                    "ReportMapper.xml 赋值的列 " + column + " 不在 t_pbccrc_report 基线中");
        }
    }

    /**
     * 读取数据库基线脚本。
     *
     * @return schema.sql 全文
     * @throws IOException 文件不存在或读取失败
     */
    private static String readSchema() throws IOException {
        assertTrue(Files.exists(SCHEMA_PATH), "找不到 " + SCHEMA_PATH.toAbsolutePath()
                + "，本测试须从项目根目录执行（mvn test 默认如此）");
        return Files.readString(SCHEMA_PATH, StandardCharsets.UTF_8);
    }

    /**
     * 读取 classpath 资源。
     *
     * @param resource 资源路径
     * @return 文件全文
     * @throws IOException 资源缺失或读取失败
     */
    private static String readClasspath(String resource) throws IOException {
        try (InputStream in = SchemaContractTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertTrue(in != null, "classpath 中找不到资源: " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 取指定表的列名集合。
     *
     * <p>mysqldump 的建表语句以 {@code ) ENGINE=InnoDB ...;} 结尾，不是 {@code );}，
     * 所以按 {@code \n) ENGINE=} 截断；没有该子句时退回下一个 CREATE TABLE 或文件末尾。</p>
     *
     * @param ddl   建表脚本全文
     * @param table 表名
     * @return 列名集合；表不存在时返回空集合
     */
    private static Set<String> columnsOf(String ddl, String table) {
        int start = ddl.indexOf("CREATE TABLE `" + table + "`");
        if (start < 0) {
            return Set.of();
        }
        int end = ddl.indexOf("\n) ENGINE=", start);
        if (end < 0) {
            end = ddl.indexOf("CREATE TABLE", start + 10);
        }
        if (end < 0) {
            end = ddl.length();
        }
        Set<String> columns = new LinkedHashSet<>();
        Matcher m = COLUMN_LINE.matcher(ddl.substring(start, end));
        while (m.find()) {
            columns.add(m.group(1));
        }
        return columns;
    }
}
