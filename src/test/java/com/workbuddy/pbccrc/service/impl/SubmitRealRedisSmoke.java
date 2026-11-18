package com.workbuddy.pbccrc.service.impl;

import com.workbuddy.pbccrc.common.BizException;
import com.workbuddy.pbccrc.common.ResultCode;
import com.workbuddy.pbccrc.config.PbccrcProperties;
import com.workbuddy.pbccrc.dao.MapperXml;
import com.workbuddy.pbccrc.dao.ReportContentMapper;
import com.workbuddy.pbccrc.dao.ReportMapper;
import com.workbuddy.pbccrc.dao.ReportQueryLogMapper;
import com.workbuddy.pbccrc.model.dto.SubmitRequest;
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
import org.redisson.Redisson;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实中间件端到端 smoke：连本机真实 Redis（db=1）与真实 MySQL，直接驱动
 * {@link SubmitServiceImpl#submit} 验证提交幂等硬约束（见 REVIEW.md）：
 * <ol>
 *   <li>首次提交成功，Redis 落下 7 天 TTL 的身份幂等锁，MySQL 落一行；</li>
 *   <li>7 天内同身份（证件+姓名）、正文不同 → 被真实 Redis trySet 原子拒绝；</li>
 *   <li>不同身份、正文相同 → 放行；</li>
 *   <li>真实 SQL {@code findRecentForUpdate} 在窗口内能查到该身份记录（二次校验口径可用）。</li>
 * </ol>
 *
 * <p>与 {@code SubmitServiceImplIdempotencyTest} 的区别：那份用内存假实现，可在无外部依赖的
 * CI 上跑；本类连真实中间件，验证假实现覆盖不到的「Redisson 真实 TTL/原子语义 + MyBatis
 * 真实 SQL 与真实表结构」。为不污染库里既有数据，使用带时间戳的独立身份，@AfterAll 全量清理
 * 本次写入的 DB 行与 Redis 锁。密码从环境变量读取，不硬编码。</p>
 *
 * <p>运行方式（默认不随 {@code mvn test} 执行，需用 -Dtest 显式选中）：
 * <pre>
 * PBCCRC_DB_PWD=xxx PBCCRC_REDIS_PWD=xxx \
 *   mvn -Dtest=SubmitRealRedisSmoke test
 * </pre>
 * </p>
 *
 * @author Alan
 * @date 2026-11-18
 */
class SubmitRealRedisSmoke {

    private static final String DB_URL = envOr("PBCCRC_DB_URL",
            "jdbc:mysql://127.0.0.1:3306/pbccrc?useUnicode=true&characterEncoding=utf8"
                    + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
    private static final String DB_USER = envOr("PBCCRC_DB_USER", "root");
    private static final String REDIS_ADDR = envOr("PBCCRC_REDIS_ADDR", "redis://127.0.0.1:6379");
    private static final int REDIS_DB = 1;
    private static final int WINDOW_DAYS = 7;

    private static RedissonClient redisson;
    private static SqlSession session;
    private static DataSource dataSource;
    private static ReportMapper reportMapper;
    private static SubmitServiceImpl service;

    private static final String TAG = "SMOKE" + System.currentTimeMillis();
    private static final String NAME_A = TAG + "_A";
    private static final String ID_A = "ID" + TAG + "A";
    private static final String NAME_B = TAG + "_B";
    private static final String ID_B = "ID" + TAG + "B";
    private static final List<String> REDIS_KEYS = new ArrayList<>();
    private static final List<Integer> REPORT_IDS = new ArrayList<>();

    private static String envOr(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isEmpty()) ? def : v;
    }

    @BeforeAll
    static void setUp() {
        String dbPwd = System.getenv("PBCCRC_DB_PWD");
        String redisPwd = System.getenv("PBCCRC_REDIS_PWD");
        assertNotNull(dbPwd, "请设置环境变量 PBCCRC_DB_PWD");
        assertNotNull(redisPwd, "请设置环境变量 PBCCRC_REDIS_PWD");

        // 真实 MySQL：手工构建 Configuration，不经过 mybatis-spring。生产环境由
        // mapper-locations 自动装载 XML，这里必须显式装载，否则 SQL 迁入 XML 后
        // 所有语句都会以 Invalid bound statement 失败（本类曾因此失效而未察觉）。
        dataSource = new UnpooledDataSource("com.mysql.cj.jdbc.Driver", DB_URL, DB_USER, dbPwd);
        Environment env = new Environment("smoke", new JdbcTransactionFactory(), dataSource);
        Configuration cfg = MapperXml.productionLike(env,
                ReportMapper.class, ReportContentMapper.class, ReportQueryLogMapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(cfg);
        session = factory.openSession(true); // autoCommit：submit() 未经 Spring 代理，无声明式事务
        reportMapper = session.getMapper(ReportMapper.class);
        ReportContentMapper contentMapper = session.getMapper(ReportContentMapper.class);
        ReportQueryLogMapper logMapper = session.getMapper(ReportQueryLogMapper.class);

        // 真实 Redis（db=1）
        Config rc = new Config();
        var server = rc.useSingleServer().setAddress(REDIS_ADDR).setDatabase(REDIS_DB);
        server.setPassword(redisPwd);
        redisson = Redisson.create(rc);

        PbccrcProperties props = new PbccrcProperties();
        props.getRedis().setSubmitLockTtlDays(WINDOW_DAYS);
        service = new SubmitServiceImpl(reportMapper, contentMapper, logMapper, props, redisson);
    }

    @AfterAll
    static void tearDown() {
        if (redisson != null) {
            for (String k : REDIS_KEYS) {
                try { redisson.getBucket(k).delete(); } catch (RuntimeException ignore) { }
            }
            redisson.shutdown();
        }
        if (!REPORT_IDS.isEmpty()) {
            try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
                String ids = String.join(",", REPORT_IDS.stream().map(String::valueOf).toList());
                st.executeUpdate("DELETE FROM t_pbccrc_report_content WHERE report_id IN (" + ids + ")");
                st.executeUpdate("DELETE FROM t_pbccrc_report_querylog WHERE report_id IN (" + ids + ")");
                st.executeUpdate("DELETE FROM t_pbccrc_report WHERE id IN (" + ids + ")");
            } catch (Exception ignore) { }
        }
        if (session != null) {
            session.close();
        }
    }

    private static SubmitRequest req(String name, String id, String html) {
        SubmitRequest r = new SubmitRequest();
        r.setCustomerName(name);
        r.setCustomerIdCard(id);
        r.setHtmlContent(html);
        r.setAppNo(TAG);
        r.setOperator("real-redis-smoke");
        return r;
    }

    private static String lockKey(String id, String name) {
        return "pbccrc:submit:lock:" + cn.hutool.crypto.digest.DigestUtil.md5Hex(id + "|" + name);
    }

    @Test
    void realRedisAndMysqlEnforceSevenDayIdentityIdempotency() {
        // 1) 首次提交成功
        Integer idA = service.submit(req(NAME_A, ID_A, "<html>正文A</html>"));
        assertNotNull(idA, "首次提交应返回主键");
        REPORT_IDS.add(idA);
        REDIS_KEYS.add(lockKey(ID_A, NAME_A));
        assertTrue(idA > 0);
        System.out.println("[smoke] 首次提交成功 reportId=" + idA + " 身份=" + NAME_A + "/" + ID_A);

        // 2) Redis 真实锁：key 存在、TTL 约 7 天
        RBucket<String> bucket = redisson.getBucket(lockKey(ID_A, NAME_A));
        assertTrue(bucket.isExists(), "首次提交后 Redis 身份锁应存在");
        long ttl = bucket.remainTimeToLive();
        long sevenDaysMs = TimeUnit.DAYS.toMillis(WINDOW_DAYS);
        assertTrue(ttl > sevenDaysMs - 60000 && ttl <= sevenDaysMs,
                "锁 TTL 应约为 7 天，实测=" + ttl + "ms");
        System.out.println("[smoke] Redis 锁存在, 剩余 TTL=" + ttl + "ms (期望≈" + sevenDaysMs + "ms)");

        // 3) 真实 MySQL 二次校验 SQL 可用：窗口内能查到该身份
        Integer recent = reportMapper.findRecentForUpdate(NAME_A, ID_A, LocalDateTime.now().minusDays(WINDOW_DAYS));
        assertEquals(idA, recent, "findRecentForUpdate 应在 7 天窗口内查到刚插入的报告");
        System.out.println("[smoke] MySQL findRecentForUpdate 命中 reportId=" + recent);

        // 4) 7 天内同身份、正文不同 → 真实 Redis trySet 原子拒绝
        BizException ex = assertThrows(BizException.class,
                () -> service.submit(req(NAME_A, ID_A, "<html>完全不同的正文B</html>")),
                "7 天内同身份（正文不同）必须被拒");
        assertEquals(ResultCode.DUPLICATE_SUBMIT, ex.getResultCode());
        System.out.println("[smoke] 同身份不同正文二次提交被拒: " + ex.getResultCode());

        // 5) 不同身份、正文相同 → 放行
        Integer idB = service.submit(req(NAME_B, ID_B, "<html>正文A</html>"));
        assertNotNull(idB, "不同身份应放行");
        REPORT_IDS.add(idB);
        REDIS_KEYS.add(lockKey(ID_B, NAME_B));
        System.out.println("[smoke] 不同身份相同正文放行 reportId=" + idB);

        // 6) 确认库里没有因重复提交多落行：两个身份各 1 行
        assertEquals(1, reportMapper.countByCustomer(ID_A, NAME_A), "身份A 应恰好 1 行");
        assertEquals(1, reportMapper.countByCustomer(ID_B, NAME_B), "身份B 应恰好 1 行");
        System.out.println("[smoke] DB 行数核对: A=1, B=1");
        System.out.println("[smoke] RESULT=PASS  规则=REVIEW.md 身份=" + NAME_A + "/" + NAME_B);
    }
}
