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
 * 提交幂等回归测试（H2 MySQL 模式）：验证业务规则——7 天窗口内同一「姓名 + 证件」
 * 不允许重复提交，去重与报告正文无关。
 *
 * <p>直接对报告主表执行 {@link ReportMapper#findRecentForUpdate}，这是幂等的 DB 二次校验
 * 查询（Redis 原子锁为必需的主判定）：窗口内命中同身份记录即视为重复；窗口外或不同身份
 * 则放行。正文哈希（content_hash）不参与身份判定。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
class SubmitIdempotencyTest {

    private SqlSessionFactory factory;
    private JdbcDataSource ds;

    @BeforeEach
    void setUp() throws Exception {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:pbccrc_idem;MODE=MySQL;DB_CLOSE_DELAY=-1");
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

    /** 7 天窗口内，同一姓名+证件即使正文不同，也应被判为重复提交。 */
    @Test
    void sameIdentityWithinWindowIsDuplicateRegardlessOfContent() throws Exception {
        insert("张三", "110101199001011234", "hash-of-report-A", LocalDateTime.now().minusDays(1));
        try (SqlSession session = factory.openSession()) {
            ReportMapper mapper = session.getMapper(ReportMapper.class);
            LocalDateTime since = LocalDateTime.now().minusDays(7);
            Integer hit = mapper.findRecentForUpdate("张三", "110101199001011234", since);
            assertNotNull(hit, "7 天内同一姓名+证件应命中，正文不同也不影响去重");
        }
    }

    /** 超过 7 天的同身份记录不算重复，同一客户可再次提交。 */
    @Test
    void sameIdentityOutsideWindowIsAllowed() throws Exception {
        insert("李四", "110101199001019999", "hash-old", LocalDateTime.now().minusDays(8));
        try (SqlSession session = factory.openSession()) {
            ReportMapper mapper = session.getMapper(ReportMapper.class);
            LocalDateTime since = LocalDateTime.now().minusDays(7);
            assertNull(mapper.findRecentForUpdate("李四", "110101199001019999", since),
                    "超过 7 天窗口应放行再次提交");
        }
    }

    /** 不同证件号（或不同姓名）互不干扰。 */
    @Test
    void differentIdentityIsAllowed() throws Exception {
        insert("王五", "110101199001011111", "hash-x", LocalDateTime.now().minusDays(1));
        try (SqlSession session = factory.openSession()) {
            ReportMapper mapper = session.getMapper(ReportMapper.class);
            LocalDateTime since = LocalDateTime.now().minusDays(7);
            assertNull(mapper.findRecentForUpdate("王五", "110101199001012222", since),
                    "不同证件号不应命中");
            assertNull(mapper.findRecentForUpdate("赵六", "110101199001011111", since),
                    "不同姓名不应命中");
        }
    }
}
