package com.workbuddy.pbccrc.dao;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 提交幂等的属性测试（property test）：不手写几个例子，而是用生成器产出大量输入，
 * 逐条断言不变量——「7 天窗口内同身份必拒 / 窗口外放行 / 不同身份放行 / 与正文无关」。
 *
 * <p>输入来自生成矩阵，不是写代码的人挑的例子，因此不会和实现对同一批边界共享盲区
 * （对应原文：属性测试让库生成输入，边缘用例不来自写实现的人）。断言写死，任何改动
 * 不得为了让测试变绿而放宽它——规则见项目根目录 REVIEW.md。</p>
 *
 * <p>刻意避开「正好第 7 天」这种毫秒级边界，防止测试 flaky（flaky 会诱使反复重跑而不是修根因）。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
class SubmitIdempotencyPropertyTest {

    private static final String[] NAMES = {"张三", "李四", "王五", "征信三", "欧阳娜娜", "A"};
    private static final String[] ID_CARDS = {
            "110101199001011234", "110101199001019999", "310101198802023456",
            "440301199505056789", "110108197012319999", "510101200001011111"
    };
    private static final String[] HASHES = {"hash-A", "hash-B", "hash-C"};
    /** 窗口内的天数，留足余量，绝不取正好 7.0。 */
    private static final double[] WITHIN_DAYS = {0, 0.5, 1, 3, 6, 6.5, 6.9};
    /** 窗口外的天数，同样留余量。 */
    private static final double[] OUTSIDE_DAYS = {7.1, 8, 15, 30, 365};
    private static final int WINDOW_DAYS = 7;
    private static final double NANOS_PER_DAY = 24d * 3600d * 1_000_000_000d;

    private SqlSessionFactory factory;
    private JdbcDataSource ds;

    @BeforeEach
    void setUp() throws Exception {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:pbccrc_idem_prop;MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS t_pbccrc_report");
            st.execute("""
                    CREATE TABLE t_pbccrc_report (
                      id INT AUTO_INCREMENT PRIMARY KEY,
                      customer_name VARCHAR(100), customer_id_card VARCHAR(50),
                      content_hash VARCHAR(64), create_time TIMESTAMP)
                    """);
        }
        Environment env = new Environment("test", new JdbcTransactionFactory(), ds);
        Configuration cfg = new Configuration(env);
        cfg.addMapper(ReportMapper.class);
        MapperXml.load(cfg, ReportMapper.class);
        factory = new SqlSessionFactoryBuilder().build(cfg);
    }

    private void insert(String name, String idCard, String contentHash, LocalDateTime createTime) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO t_pbccrc_report (customer_name, customer_id_card, content_hash, create_time) VALUES (?,?,?,?)")) {
            ps.setString(1, name);
            ps.setString(2, idCard);
            ps.setString(3, contentHash);
            ps.setObject(4, createTime);
            ps.executeUpdate();
        }
    }

    private void clear() throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM t_pbccrc_report");
        }
    }

    private Integer query(String name, String idCard, LocalDateTime since) {
        try (SqlSession session = factory.openSession()) {
            return session.getMapper(ReportMapper.class).findRecentForUpdate(name, idCard, since);
        }
    }

    private static String label(String name, String idCard, String hash, double days) {
        return "name=" + name + ", id=" + idCard + ", content=" + hash + ", daysAgo=" + days;
    }

    @Test
    void identityWithinWindowIsAlwaysDuplicateRegardlessOfContent() throws Exception {
        int cases = 0;
        for (String name : NAMES) {
            for (String id : ID_CARDS) {
                for (String hash : HASHES) {
                    for (double days : WITHIN_DAYS) {
                        clear();
                        insert(name, id, hash, LocalDateTime.now().minusNanos((long) (days * NANOS_PER_DAY)));
                        Integer hit = query(name, id, LocalDateTime.now().minusDays(WINDOW_DAYS));
                        assertNotNull(hit, "窗口内同身份必须判重 —— " + label(name, id, hash, days));
                        cases++;
                    }
                }
            }
        }
        assertTrue(cases >= 500, "生成用例数应足够大，实际=" + cases);
    }

    @Test
    void identityOutsideWindowIsAlwaysAllowed() throws Exception {
        int cases = 0;
        for (String name : NAMES) {
            for (String id : ID_CARDS) {
                for (String hash : HASHES) {
                    for (double days : OUTSIDE_DAYS) {
                        clear();
                        insert(name, id, hash, LocalDateTime.now().minusNanos((long) (days * NANOS_PER_DAY)));
                        assertNull(query(name, id, LocalDateTime.now().minusDays(WINDOW_DAYS)),
                                "窗口外同身份必须放行 —— " + label(name, id, hash, days));
                        cases++;
                    }
                }
            }
        }
        assertTrue(cases >= 400, "生成用例数应足够大，实际=" + cases);
    }

    @Test
    void identityIsTheDimensionNotContent() throws Exception {
        int cases = 0;
        for (String name : NAMES) {
            for (String id : ID_CARDS) {
                clear();
                // 同一身份、两段完全不同的正文都在窗口内：仍只能判重，正文不参与维度
                insert(name, id, "hash-A", LocalDateTime.now().minusDays(2));
                insert(name, id, "hash-B", LocalDateTime.now().minusDays(1));
                LocalDateTime since = LocalDateTime.now().minusDays(WINDOW_DAYS);
                assertNotNull(query(name, id, since),
                        "正文不同不影响同身份判重 —— name=" + name + " id=" + id);
                // 身份不同则放行，即使正文与已有记录完全一致
                assertNull(query(name, id + "X", since), "不同证件号应放行 —— name=" + name + " id=" + id);
                assertNull(query(name + "X", id, since), "不同姓名应放行 —— name=" + name + " id=" + id);
                cases++;
            }
        }
        assertTrue(cases >= 30, "生成用例数应足够大，实际=" + cases);
    }
}
