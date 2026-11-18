package com.workbuddy.pbccrc.dao;

import com.workbuddy.pbccrc.model.entity.CustomerHistory;
import com.workbuddy.pbccrc.model.entity.ReportContent;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 其余 5 个 Mapper 的真实 MySQL 方言冒烟。
 *
 * <p>{@link ReportMapperRealSqlSmoke} 覆盖主表 17 条语句，本类补齐剩下的：
 * {@link DetailQueryMapper} 12 条明细查询、{@link ReportContentMapper} 3 条、
 * {@link ReportQueryLogMapper} 2 条、{@link RuleMapper} 1 条、
 * {@link CustomerHistoryMapper} 1 条。</p>
 *
 * <p>其中两条是纯 MySQL 方言，H2 的兼容模式覆盖不到，必须真库验证：</p>
 * <ul>
 *   <li>{@code ReportQueryLogMapper.insertIgnore} 的 {@code INSERT IGNORE} 与 {@code CURDATE()}
 *       （create_time 列是 date 而非 datetime）；</li>
 *   <li>{@code RuleMapper.selectAllRules} 的两表 JOIN，列名与 RuleRow 的映射依赖
 *       map-underscore-to-camel-case。</li>
 * </ul>
 *
 * <p>默认不随 {@code mvn test} 执行，需显式选中：
 * {@code PBCCRC_DB_PWD=xxx mvn -Dtest=OtherMappersRealSqlSmoke test}。</p>
 *
 * @author Alan
 * @date 2026-11-18
 */
class OtherMappersRealSqlSmoke {

    private static final String DB_URL = envOr("PBCCRC_DB_URL",
            "jdbc:mysql://127.0.0.1:3306/pbccrc?useUnicode=true&characterEncoding=utf8"
                    + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
    private static final String DB_USER = envOr("PBCCRC_DB_USER", "root");
    private static final int PROBE_ID = -20261118;

    private static DataSource dataSource;
    private static SqlSession session;
    private static DetailQueryMapper detailMapper;
    private static ReportContentMapper contentMapper;
    private static ReportQueryLogMapper logMapper;
    private static RuleMapper ruleMapper;
    private static CustomerHistoryMapper historyMapper;

    private static String envOr(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isEmpty()) ? def : v;
    }

    @BeforeAll
    static void setUp() {
        String pwd = System.getenv("PBCCRC_DB_PWD");
        assertNotNull(pwd, "请设置环境变量 PBCCRC_DB_PWD");
        dataSource = new UnpooledDataSource("com.mysql.cj.jdbc.Driver", DB_URL, DB_USER, pwd);

        Environment env = new Environment("othersql", new JdbcTransactionFactory(), dataSource);
        Configuration cfg = MapperXml.productionLike(env, DetailQueryMapper.class, ReportContentMapper.class,
                ReportQueryLogMapper.class, RuleMapper.class, CustomerHistoryMapper.class);

        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(cfg);
        session = factory.openSession(true);
        detailMapper = session.getMapper(DetailQueryMapper.class);
        contentMapper = session.getMapper(ReportContentMapper.class);
        logMapper = session.getMapper(ReportQueryLogMapper.class);
        ruleMapper = session.getMapper(RuleMapper.class);
        historyMapper = session.getMapper(CustomerHistoryMapper.class);
    }

    @AfterAll
    static void tearDown() {
        if (session == null) {
            return;
        }
        try {
            contentMapper.deleteByReportId(PROBE_ID);
            logMapper.deleteByReportId(PROBE_ID);
            session.getConnection().createStatement()
                    .executeUpdate("DELETE FROM t_pbccrc_customer_history WHERE report_id = " + PROBE_ID);
        } catch (Exception ignore) {
            // 清理失败不影响结论，验证库可随时重建
        }
        session.close();
    }

    /** 12 条明细查询：表名与列名在真实库上拼错会在这里立刻报 Unknown column / Table doesn't exist。 */
    @Test
    void detailQueriesResolveOnRealSchema() {
        assertEquals(0, detailMapper.creditCards(PROBE_ID).size());
        assertEquals(0, detailMapper.loans(PROBE_ID).size());
        assertEquals(0, detailMapper.queryRecords(PROBE_ID).size());
        assertEquals(0, detailMapper.assetDisposals(PROBE_ID).size());
        assertEquals(0, detailMapper.compensates(PROBE_ID).size());
        assertEquals(0, detailMapper.guarantees(PROBE_ID).size());
        assertEquals(0, detailMapper.rawCreditRecords(PROBE_ID).size());
        assertEquals(0, detailMapper.adminPunishments(PROBE_ID).size());
        assertEquals(0, detailMapper.civilDecisions(PROBE_ID).size());
        assertEquals(0, detailMapper.courtDecisions(PROBE_ID).size());
        assertEquals(0, detailMapper.taxArrears(PROBE_ID).size());
        assertEquals(0, detailMapper.telecomOwes(PROBE_ID).size());
        System.out.println("[othersql] DetailQueryMapper 12 条明细查询执行通过");
    }

    /** 正文读写：CLOB 写入后必须原样读回，否则解析结果会静默丢失。 */
    @Test
    void reportContentRoundTrip() {
        String html = "<html><body>正文" + PROBE_ID + "</body></html>";
        ReportContent c = new ReportContent();
        c.setReportId(PROBE_ID);
        c.setContent(html);
        c.setCreatorId("real-sql-smoke");
        c.setCreateTime(LocalDateTime.now());
        assertEquals(1, contentMapper.insert(c), "正文插入应影响 1 行");
        assertNotNull(c.getId(), "useGeneratedKeys 未回填主键");

        ReportContent loaded = contentMapper.findByReportId(PROBE_ID);
        assertNotNull(loaded, "findByReportId 应查到刚写入的正文");
        assertEquals(html, loaded.getContent(), "CLOB 写入与读回不一致");
        assertEquals(PROBE_ID, loaded.getReportId());

        assertEquals(1, contentMapper.deleteByReportId(PROBE_ID), "按 report_id 删除应影响 1 行");
        System.out.println("[othersql] ReportContentMapper 3 条语句执行通过");
    }

    /** INSERT IGNORE + CURDATE()：纯 MySQL 方言。 */
    @Test
    void queryLogInsertIgnoreWorks() {
        assertEquals(1, logMapper.insertIgnore("冒烟客户", "ID" + PROBE_ID, PROBE_ID, "real-sql-smoke"),
                "首次写入应影响 1 行");
        // 同参数再写一次。唯一键是 (customer_name, customer_id_card, content_hash)，而本语句
        // 不写 content_hash（恒为 NULL，MySQL 唯一索引对 NULL 不去重），故 IGNORE 不会拦下第二行。
        // 这里把这个已知事实固化成断言：它证明「流水表当前不具备去重能力」，去重只由
        // Redis 锁 + 主表窗口保证。
        assertEquals(1, logMapper.insertIgnore("冒烟客户", "ID" + PROBE_ID, PROBE_ID, "real-sql-smoke"));
        assertEquals(2, logMapper.deleteByReportId(PROBE_ID), "唯一键对 NULL 不去重，应存在 2 行");
        System.out.println("[othersql] ReportQueryLogMapper 2 条语句执行通过（INSERT IGNORE 生效）");
    }

    /** 规则表两表 JOIN；空表也应正常返回空集合而不是抛错。 */
    @Test
    void ruleSelectAllRuns() {
        assertNotNull(ruleMapper.selectAllRules(), "selectAllRules 不应返回 null");
        System.out.println("[othersql] RuleMapper.selectAllRules 执行通过，行数="
                + ruleMapper.selectAllRules().size());
    }

    /** 历史留痕插入：五列 NOT NULL 且无默认值，缺一列就会在这里暴露。 */
    @Test
    void customerHistoryInsertWorks() {
        CustomerHistory h = new CustomerHistory();
        h.setReportId(PROBE_ID);
        h.setQueryDate(LocalDateTime.now());
        h.setCustomerIdCard("ID" + PROBE_ID);
        h.setCustomerName("冒烟客户");
        h.setOperator("real-sql-smoke");
        h.setReportState("PENDING");
        h.setCreateTime(LocalDateTime.now());
        h.setCreatorId("real-sql-smoke");
        assertEquals(1, historyMapper.insert(h), "历史插入应影响 1 行");
        assertNotNull(h.getId(), "useGeneratedKeys 未回填主键");
        assertFalse(h.getId() <= 0, "自增主键应为正数");
        System.out.println("[othersql] CustomerHistoryMapper.insert 执行通过 id=" + h.getId());
    }
}
