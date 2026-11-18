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
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 状态机回归测试（H2 MySQL 模式）：验证卡死的 PENDING 报告能被重置重投。
 *
 * <p>对应代码评审 2.2：修复前 findStuck 会返回 PENDING，但 resetToPending 只接受
 * PARSING/FAILED，导致重置返回 0，补偿每轮空转，报告永久卡死。这里直接注入一份
 * 心跳超时的 PENDING 报告并断言 resetToPending 成功（返回 1）——这正是被修复的行为。
 * findStuck 使用 MySQL 专有 DATE_SUB，H2 不支持，故不在单测中覆盖其 SQL。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
class ReportStateMachineTest {

    private JdbcDataSource ds;
    private SqlSessionFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:pbccrc_state;MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS t_pbccrc_report");
            // 列类型与 db/schema.sql 保持一致：早先用 VARCHAR 代替 datetime、DECIMAL 代替 int，
            // H2 会默默接受，生产上却会因类型不匹配报错，测出来的「通过」并不可信
            st.execute("""
                    CREATE TABLE t_pbccrc_report (
                      id INT AUTO_INCREMENT PRIMARY KEY,
                      customer_id_card VARCHAR(50), customer_name VARCHAR(100), app_no VARCHAR(50),
                      status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                      retry_count INT NOT NULL DEFAULT 0,
                      heartbeat_time TIMESTAMP,
                      content_hash VARCHAR(64), error_msg CLOB,
                      create_time TIMESTAMP, creator_id VARCHAR(50), modify_time TIMESTAMP,
                      name VARCHAR(255), cert_type VARCHAR(100), cert_no VARCHAR(100),
                      query_time TIMESTAMP, report_time TIMESTAMP, marital_status VARCHAR(100),
                      report_no VARCHAR(100), description CLOB,
                      credit_record_description CLOB, query_record_description CLOB,
                      common_record_description CLOB, common_record_detail CLOB,
                      credit_record_summary CLOB,
                      credit_card_total INT, credit_card_active INT, credit_card_overdue INT,
                      credit_card_serious_overdue INT, credit_card_guarantee INT,
                      housing_loan_total INT, housing_loan_active INT, housing_loan_overdue INT,
                      housing_loan_serious_overdue INT, housing_loan_guarantee INT,
                      other_loan_total INT, other_loan_active INT, other_loan_overdue INT,
                      other_loan_serious_overdue INT, other_loan_guarantee INT,
                      asset_total INT, repayment_total INT)
                    """);
        }
        Environment env = new Environment("test", new JdbcTransactionFactory(), ds);
        Configuration cfg = new Configuration(env);
        cfg.addMapper(ReportMapper.class);
        factory = new SqlSessionFactoryBuilder().build(cfg);
    }

    /** 卡死的 PENDING 报告必须能被 resetToPending 成功重置（修复前这里返回 0）。 */
    @Test
    void stuckPendingReportCanBeReset() throws Exception {
        int id;
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            // 注入一份心跳超时（1 小时前）的 PENDING 报告
            st.execute("""
                    INSERT INTO t_pbccrc_report (customer_id_card, customer_name, status, heartbeat_time)
                    VALUES ('110101199001011234', '张三', 'PENDING',
                            DATEADD('MINUTE', -60, CURRENT_TIMESTAMP()))
                    """);
            try (ResultSet rs = st.executeQuery("SELECT MAX(id) FROM t_pbccrc_report")) {
                rs.next();
                id = rs.getInt(1);
            }
        }
        try (SqlSession session = factory.openSession(true)) {
            ReportMapper mapper = session.getMapper(ReportMapper.class);
            // 核心断言：修复前返回 0，导致补偿任务永远空转、报告卡死
            assertEquals(1, mapper.resetToPending(id), "PENDING 报告必须可被重置重投");
        }
        // 重置后心跳被刷新，状态仍为 PENDING
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT status, heartbeat_time FROM t_pbccrc_report WHERE id=" + id)) {
            assertTrue(rs.next());
            assertEquals("PENDING", rs.getString(1));
            assertNotNull(rs.getTimestamp(2), "重置应刷新心跳时间");
        }
    }

    /** 评审 3.3：手动重试只重置 FAILED，拒绝把 PARSING 重置，避免并发重复解析。 */
    @Test
    void manualRetryRejectsParsingButAllowsFailed() throws Exception {
        int parsingId;
        int failedId;
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO t_pbccrc_report (customer_id_card, customer_name, status, heartbeat_time) "
                    + "VALUES ('110101199001010001', '甲', 'PARSING', CURRENT_TIMESTAMP())");
            st.execute("INSERT INTO t_pbccrc_report (customer_id_card, customer_name, status, heartbeat_time) "
                    + "VALUES ('110101199001010002', '乙', 'FAILED', CURRENT_TIMESTAMP())");
            try (ResultSet rs = st.executeQuery("SELECT MAX(id) FROM t_pbccrc_report")) {
                rs.next();
                failedId = rs.getInt(1);
            }
            parsingId = failedId - 1;
        }
        try (SqlSession session = factory.openSession(true)) {
            ReportMapper mapper = session.getMapper(ReportMapper.class);
            assertEquals(0, mapper.resetFailedToPending(parsingId), "PARSING 报告不允许手动重试重置");
            assertEquals(1, mapper.resetFailedToPending(failedId), "FAILED 报告允许手动重试重置");
        }
    }

    /** 评审 3.6：markSuccess 带 PARSING 的 CAS 条件，状态已变更时影响 0 行。 */
    @Test
    void markSuccessOnlyWhenParsing() throws Exception {
        int parsingId;
        int failedId;
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO t_pbccrc_report (customer_id_card, customer_name, status, heartbeat_time) "
                    + "VALUES ('110101199001010003', '丙', 'PARSING', CURRENT_TIMESTAMP())");
            st.execute("INSERT INTO t_pbccrc_report (customer_id_card, customer_name, status, heartbeat_time) "
                    + "VALUES ('110101199001010004', '丁', 'FAILED', CURRENT_TIMESTAMP())");
            try (ResultSet rs = st.executeQuery("SELECT MAX(id) FROM t_pbccrc_report")) {
                rs.next();
                failedId = rs.getInt(1);
            }
            parsingId = failedId - 1;
        }
        com.workbuddy.pbccrc.model.entity.Report report = new com.workbuddy.pbccrc.model.entity.Report();
        try (SqlSession session = factory.openSession(true)) {
            ReportMapper mapper = session.getMapper(ReportMapper.class);
            report.setName("报告头");
            report.setId(failedId);
            assertEquals(0, mapper.markSuccess(report), "FAILED 报告不应被 markSuccess 覆盖");
            report.setId(parsingId);
            assertEquals(1, mapper.markSuccess(report), "PARSING 报告可正常标记成功");
        }
    }

    /** 非 PENDING/PARSING/FAILED 状态（如 SUCCESS）不应被重置。 */
    @Test
    void successReportCannotBeReset() throws Exception {
        int id;
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("""
                    INSERT INTO t_pbccrc_report (customer_id_card, customer_name, status, heartbeat_time)
                    VALUES ('110101199001019999', '李四', 'SUCCESS', CURRENT_TIMESTAMP())
                    """);
            try (ResultSet rs = st.executeQuery("SELECT MAX(id) FROM t_pbccrc_report")) {
                rs.next();
                id = rs.getInt(1);
            }
        }
        try (SqlSession session = factory.openSession(true)) {
            ReportMapper mapper = session.getMapper(ReportMapper.class);
            assertEquals(0, mapper.resetToPending(id), "SUCCESS 报告不应被重置");
        }
    }
}
