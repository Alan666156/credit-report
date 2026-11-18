package com.workbuddy.pbccrc.dao;

import com.workbuddy.pbccrc.model.dto.ReportVO;
import com.workbuddy.pbccrc.model.entity.Report;
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
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 MySQL 方言冒烟：把 {@link ReportMapper} 的 17 条语句在真实库上逐条打一遍。
 *
 * <p>为什么必须有这一层：{@code MapperXmlLoadTest} 只证明「语句能绑定」，
 * {@code SchemaContractTest} 只做静态列名比对，DAO 层单测跑在 H2 的 MySQL 兼容模式上。
 * 而本轮 SQL 从注解迁入 XML 之后，真正的风险全在方言与映射口径上——</p>
 * <ul>
 *   <li>{@code findStuck} 的 {@code DATE_SUB(NOW(), INTERVAL ? MINUTE)}，H2 不支持；</li>
 *   <li>{@code LIMIT #{offset}, #{pageSize}}（offset,rows 语法），H2 语义不同；</li>
 *   <li>{@code markSuccess} 30 个赋值列 + 4 个常量列，列序与参数序是否对齐只能靠真插一行；</li>
 *   <li>{@code map-underscore-to-camel-case} 与 {@code jdbc-type-for-null} 来自
 *       application.yml，手工构建的 Configuration 默认值与生产不同，映射错位会静默返回 null。</li>
 * </ul>
 *
 * <p>因此本类显式把 Configuration 的三项调到与 application.yml 一致，否则测出来的口径
 * 不是生产口径，通过了也不代表线上可用。</p>
 *
 * <p>默认不随 {@code mvn test} 执行（类名不以 Test 结尾），需显式选中：
 * <pre>
 * PBCCRC_DB_PWD=xxx PBCCRC_DB_URL=jdbc:mysql://127.0.0.1:3306/pbccrc_verify?... \
 *   mvn -Dtest=ReportMapperRealSqlSmoke test
 * </pre>
 * 数据用带时间戳的唯一身份标记，@AfterAll 全量清理，不触碰库里既有行。</p>
 *
 * @author Alan
 * @date 2026-11-18
 */
class ReportMapperRealSqlSmoke {

    private static final String DB_URL = envOr("PBCCRC_DB_URL",
            "jdbc:mysql://127.0.0.1:3306/pbccrc?useUnicode=true&characterEncoding=utf8"
                    + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
    private static final String DB_USER = envOr("PBCCRC_DB_USER", "root");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String TAG = "SQLSMOKE" + System.currentTimeMillis();
    private static final String NAME = TAG + "_NAME";
    private static final String ID_CARD = "ID" + TAG;

    private static DataSource dataSource;
    private static SqlSession session;
    private static ReportMapper mapper;
    private static final List<Integer> IDS = new ArrayList<>();

    private static String envOr(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isEmpty()) ? def : v;
    }

    @BeforeAll
    static void setUp() {
        String pwd = System.getenv("PBCCRC_DB_PWD");
        assertNotNull(pwd, "请设置环境变量 PBCCRC_DB_PWD");
        dataSource = new UnpooledDataSource("com.mysql.cj.jdbc.Driver", DB_URL, DB_USER, pwd);

        Environment env = new Environment("realsql", new JdbcTransactionFactory(), dataSource);
        // 口径必须与 application.yml 一致，否则映射错位会静默返回 null
        Configuration cfg = MapperXml.productionLike(env, ReportMapper.class);

        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(cfg);
        session = factory.openSession(true);
        mapper = session.getMapper(ReportMapper.class);
    }

    @AfterAll
    static void tearDown() {
        if (session != null && !IDS.isEmpty()) {
            try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
                st.executeUpdate("DELETE FROM t_pbccrc_report WHERE id IN (" + join(IDS) + ")");
            } catch (Exception ignore) {
                // 清理失败不影响结论，验证库可随时重建
            }
            session.close();
        }
    }

    private static String join(List<Integer> ids) {
        StringBuilder sb = new StringBuilder();
        for (Integer id : ids) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        return sb.toString();
    }

    /** 插入一条待解析报告，返回主键。 */
    private static int insertPending(String name, String idCard, String appNo) {
        Report r = new Report();
        r.setCustomerName(name);
        r.setCustomerIdCard(idCard);
        r.setAppNo(appNo);
        r.setStatus("PENDING");
        r.setRetryCount(0);
        r.setCreateTime(LocalDateTime.now());
        r.setCreatorId("real-sql-smoke");
        assertEquals(1, mapper.insert(r), "insert 应影响 1 行");
        assertNotNull(r.getId(), "useGeneratedKeys 未回填主键");
        IDS.add(r.getId());
        return r.getId();
    }

    /**
     * 状态机与补偿扫描：insert / claim / heartbeat / markSuccess / markFailed /
     * resetToPending / resetFailedToPending / findStuck / findRetryable / findDeadLetters。
     */
    @Test
    void stateMachineStatements() {
        int okId = insertPending(NAME, ID_CARD, TAG + "-OK");

        // findById + 驼峰映射：map-underscore-to-camel-case 失效时这里是 null
        Report loaded = mapper.findById(okId);
        assertNotNull(loaded, "findById 应查到刚插入的行");
        assertEquals(NAME, loaded.getCustomerName(), "驼峰映射失效：customer_name 未映射到 customerName");
        assertEquals(ID_CARD, loaded.getCustomerIdCard());
        assertEquals("PENDING", loaded.getStatus());

        // claim：PENDING -> PARSING，重复 claim 被 CAS 拒绝
        assertEquals(1, mapper.claim(okId), "PENDING 应可抢占");
        assertEquals("PARSING", mapper.findById(okId).getStatus());
        assertEquals(0, mapper.claim(okId), "已 PARSING 的报告不应被再次抢占");

        // heartbeat：仅 PARSING 可刷新
        assertEquals(1, mapper.heartbeat(okId), "PARSING 应可刷新心跳");

        // markSuccess：34 个 SET 项，列序错位会体现在具体字段上
        Report payload = successPayload(okId);
        assertEquals(1, mapper.markSuccess(payload), "PARSING 应可置成功");
        Report done = mapper.findById(okId);
        assertEquals("SUCCESS", done.getStatus());
        assertEquals("张三", done.getName());
        assertEquals("身份证", done.getCertType());
        assertEquals("110101199001011234", done.getCertNo());
        assertEquals("PBCCRC20261118000001", done.getReportNo());
        assertEquals("已婚", done.getMaritalStatus());
        assertEquals(7, done.getCreditCardTotal());
        assertEquals(6, done.getCreditCardActive());
        assertEquals(1, done.getCreditCardOverdue());
        assertEquals(3, done.getHousingLoanTotal());
        assertEquals(2, done.getOtherLoanTotal());
        assertEquals(11, done.getAssetTotal());
        assertEquals(22, done.getRepaymentTotal());
        assertEquals("信贷记录描述", done.getCreditRecordDescription());
        assertEquals("查询记录描述", done.getQueryRecordDescription());
        assertEquals("公共记录描述", done.getCommonRecordDescription());
        assertNotNull(done.getQueryTime());
        assertNotNull(done.getReportTime());
        assertNotNull(done.getModifyTime());
        assertNotNull(done.getHeartbeatTime());
        assertNull(done.getErrorMsg(), "成功时 error_msg 应被显式清空（依赖 jdbc-type-for-null）");

        // CAS 守卫：成功后不能被二次置成功，也不能被迟到的失败覆盖
        assertEquals(0, mapper.markSuccess(payload), "SUCCESS 后不应再被 markSuccess");
        assertEquals(0, mapper.markFailed(okId, "迟到的失败"), "SUCCESS 不应被迟到的 markFailed 覆盖");
        assertEquals(0, mapper.resetToPending(okId), "SUCCESS 不在可重置集合内");
        assertEquals("SUCCESS", mapper.findById(okId).getStatus());

        // 失败链路：claim -> markFailed（retry_count +1）-> resetFailedToPending
        int failId = insertPending(NAME, ID_CARD, TAG + "-FAIL");
        assertEquals(1, mapper.claim(failId));
        assertEquals(1, mapper.markFailed(failId, "模拟解析失败"), "PARSING 应可置失败");
        Report failed = mapper.findById(failId);
        assertEquals("FAILED", failed.getStatus());
        assertEquals(1, failed.getRetryCount(), "markFailed 应累加重试次数");
        assertEquals("模拟解析失败", failed.getErrorMsg());
        assertEquals(1, mapper.resetFailedToPending(failId), "FAILED 应可回到 PENDING");
        assertEquals("PENDING", mapper.findById(failId).getStatus());

        // findStuck：DATE_SUB(NOW(), INTERVAL ? MINUTE)，H2 覆盖不到
        int stuckId = insertPending(NAME, ID_CARD, TAG + "-STUCK");
        assertEquals(1, mapper.claim(stuckId));
        exec("UPDATE t_pbccrc_report SET heartbeat_time = DATE_SUB(NOW(), INTERVAL 2 HOUR) WHERE id = " + stuckId);
        List<Integer> stuck = mapper.findStuck(30);
        assertTrue(stuck.contains(stuckId), "心跳超 2 小时、阈值 30 分钟应被判为卡死");
        assertFalse(stuck.contains(okId), "SUCCESS 不应出现在卡死列表");

        // findRetryable / findDeadLetters：按 retry_count 划分重试与死信
        int retryId = insertPending(NAME, ID_CARD, TAG + "-RETRY");
        assertEquals(1, mapper.claim(retryId));
        assertEquals(1, mapper.markFailed(retryId, "待重试"));
        assertTrue(mapper.findRetryable(5).contains(retryId), "retry_count=1 < 5 应可重试");

        int deadId = insertPending(NAME, ID_CARD, TAG + "-DEAD");
        assertEquals(1, mapper.claim(deadId));
        exec("UPDATE t_pbccrc_report SET status = 'FAILED', retry_count = 5 WHERE id = " + deadId);
        List<Report> dead = mapper.findDeadLetters(5);
        Report deadRow = dead.stream().filter(d -> deadId == d.getId()).findFirst().orElse(null);
        assertNotNull(deadRow, "retry_count=5 >= 5 应进入死信");
        assertEquals(NAME, deadRow.getCustomerName(), "findDeadLetters 的驼峰映射失效");
        assertFalse(mapper.findRetryable(5).contains(deadId), "达上限的报告不应再可重试");

        System.out.println("[realsql] 状态机/补偿 10 条语句执行通过");
    }

    /**
     * 查询与分页：findByCustomer / countByCustomer / findRecentForUpdate /
     * findVOById / countByCondition / findByCondition（含 LIMIT offset,pageSize）。
     */
    @Test
    void queryAndPagingStatements() {
        String name = NAME + "_Q";
        String idCard = ID_CARD + "Q";
        int id1 = insertPending(name, idCard, TAG + "-Q1");
        int id2 = insertPending(name, idCard, TAG + "-Q2");

        // findByCustomer + LIMIT offset,pageSize
        List<ReportVO> page0 = mapper.findByCustomer(idCard, name, 0, 10);
        assertEquals(2, page0.size(), "同身份应有 2 条");
        assertEquals(name, page0.get(0).getCustomerName(), "ReportVO 驼峰映射失效");

        List<ReportVO> page1 = mapper.findByCustomer(idCard, name, 1, 10);
        assertEquals(1, page1.size(), "offset=1 应只剩 1 条");
        assertNotEquals(page0.get(0).getId(), page1.get(0).getId(), "分页应生效，两页首行不能相同");

        assertEquals(2, mapper.countByCustomer(idCard, name), "countByCustomer 口径应与列表一致");

        // findRecentForUpdate：窗口内命中、窗口外不命中
        assertEquals(id2, mapper.findRecentForUpdate(name, idCard, LocalDateTime.now().minusDays(7)),
                "7 天窗口内应命中最新一条");
        assertNull(mapper.findRecentForUpdate(name, idCard, LocalDateTime.now().plusDays(1)),
                "窗口起点在未来时不应命中");

        // findVOById
        ReportVO vo = mapper.findVOById(id1);
        assertNotNull(vo);
        assertEquals(idCard, vo.getCustomerIdCard());
        assertEquals("PENDING", vo.getStatus());

        // countByCondition / findByCondition：AdminWhere 的全空分支
        assertTrue(mapper.countByCondition(null, null, null, null) >= 2, "无条件统计应包含本用例数据");
        List<ReportVO> all = mapper.findByCondition(null, null, null, null, 0, 10);
        assertFalse(all.isEmpty(), "无条件分页不应为空");

        // 模糊匹配分支：LIKE CONCAT('%', ?, '%')
        assertEquals(2, mapper.countByCondition(name, null, null, null), "customerName 模糊匹配应为 2 条");
        assertEquals(2, mapper.findByCondition(name, null, null, null, 0, 10).size());
        exec("UPDATE t_pbccrc_report SET name = '李四' WHERE id = " + id1);
        assertEquals(1, mapper.countByCondition(null, "李四", null, null), "name 模糊匹配应为 1 条");

        // 时间范围分支：参数以字符串下发，格式必须是 yyyy-MM-dd HH:mm:ss
        String start = LocalDateTime.now().minusHours(1).format(FMT);
        String end = LocalDateTime.now().plusHours(1).format(FMT);
        assertEquals(2, mapper.countByCondition(name, null, start, end), "时间范围内的 2 条应命中");
        assertEquals(0, mapper.countByCondition(name, null,
                LocalDateTime.now().plusDays(1).format(FMT),
                LocalDateTime.now().plusDays(2).format(FMT)), "未来区间不应命中");

        System.out.println("[realsql] 查询/分页 7 条语句执行通过");
    }

    /** 构造 markSuccess 的全部 30 个赋值字段，用于验证列序与参数序一一对齐。 */
    private static Report successPayload(int id) {
        Report r = new Report();
        r.setId(id);
        r.setName("张三");
        r.setCertType("身份证");
        r.setCertNo("110101199001011234");
        r.setQueryTime(LocalDateTime.now().minusDays(2));
        r.setReportTime(LocalDateTime.now().minusDays(1));
        r.setMaritalStatus("已婚");
        r.setReportNo("PBCCRC20261118000001");
        r.setDescription("报告说明");
        r.setCreditRecordDescription("信贷记录描述");
        r.setQueryRecordDescription("查询记录描述");
        r.setCommonRecordDescription("公共记录描述");
        r.setCommonRecordDetail("公共记录明细");
        r.setCreditRecordSummary("信贷记录概要");
        r.setCreditCardTotal(7);
        r.setCreditCardActive(6);
        r.setCreditCardOverdue(1);
        r.setCreditCardSeriousOverdue(0);
        r.setCreditCardGuarantee(2);
        r.setHousingLoanTotal(3);
        r.setHousingLoanActive(2);
        r.setHousingLoanOverdue(0);
        r.setHousingLoanSeriousOverdue(0);
        r.setHousingLoanGuarantee(1);
        r.setOtherLoanTotal(2);
        r.setOtherLoanActive(1);
        r.setOtherLoanOverdue(1);
        r.setOtherLoanSeriousOverdue(0);
        r.setOtherLoanGuarantee(1);
        r.setAssetTotal(11);
        r.setRepaymentTotal(22);
        r.setErrorMsg(null);
        return r;
    }

    private static void exec(String sql) {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate(sql);
        } catch (Exception e) {
            throw new IllegalStateException("执行辅助 SQL 失败: " + sql, e);
        }
    }
}
