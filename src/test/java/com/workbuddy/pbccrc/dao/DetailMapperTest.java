package com.workbuddy.pbccrc.dao;

import com.workbuddy.pbccrc.model.entity.QueryRecord;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 验证通用明细写入器生成的 INSERT/DELETE 能真实执行（H2 MySQL 模式）。 */
class DetailMapperTest {

    @Test
    void insertAndDeleteByReportId() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:pbccrc_detail;MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE t_pbccrc_query_record (
                      id INT AUTO_INCREMENT PRIMARY KEY,
                      report_id INT, query_date VARCHAR(100), type VARCHAR(20),
                      query_no VARCHAR(100), operator VARCHAR(255), query_reason CLOB,
                      query_operator VARCHAR(255), creator_id VARCHAR(50),
                      create_time TIMESTAMP, modifier_id VARCHAR(50), modify_time TIMESTAMP)
                    """);
        }
        Environment env = new Environment("test", new JdbcTransactionFactory(), ds);
        Configuration cfg = new Configuration(env);
        cfg.addMapper(DetailMapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(cfg);

        QueryRecord r = new QueryRecord();
        r.setReportId(7);
        r.setQueryNo("1");
        r.setQueryDate("2018年5月7日");
        r.setType("1");
        r.setOperator("南京银行/g*b26");
        r.setQueryOperator("南京银行");
        r.setQueryReason("贷款审批");

        try (SqlSession session = factory.openSession(true)) {
            DetailMapper mapper = session.getMapper(DetailMapper.class);
            assertEquals(1, mapper.insertBatch("t_pbccrc_query_record", List.of(r)));
        }
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT report_id, query_operator FROM t_pbccrc_query_record")) {
            assertTrue(rs.next());
            assertEquals(7, rs.getInt(1));
            assertEquals("南京银行", rs.getString(2));
        }
        try (SqlSession session = factory.openSession(true)) {
            DetailMapper mapper = session.getMapper(DetailMapper.class);
            assertEquals(1, mapper.deleteByReportId("t_pbccrc_query_record", 7));
        }
    }
}
