package com.workbuddy.pbccrc.dao;

import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mapper 接口与 SQL 的绑定契约。
 *
 * <p>SQL 从注解搬进 XML 之后，最典型的失误是「接口方法改了名 / XML 里 id 打错 / 新增方法
 * 忘了补 XML」——这类错误不体现在编译期，只在首个请求打到时抛
 * {@code Invalid bound statement}。本测试把 6 个 XML Mapper 的每个方法逐一断言能在
 * {@link Configuration} 里找到对应 statement，把这个检查提前到构建阶段。</p>
 *
 * <p>同时也约束 {@link DetailMapper}：它的 SQL 由 {@code DetailSqlProvider} 生成而非 XML，
 * 这里一并断言其方法可绑定，防止有人为了「统一」把它也搬走。</p>
 *
 * @author Alan
 * @date 2026-11-18
 */
class MapperXmlLoadTest {

    /** 走 XML 的 Mapper。新增 XML Mapper 时必须加到这里，否则不会被本测试覆盖。 */
    private static final List<Class<?>> XML_MAPPERS = List.of(
            ReportMapper.class,
            ReportContentMapper.class,
            ReportQueryLogMapper.class,
            DetailQueryMapper.class,
            RuleMapper.class,
            CustomerHistoryMapper.class
    );

    @Test
    void everyXmlMapperMethodHasMappedStatement() {
        Configuration cfg = new Configuration();
        MapperXml.load(cfg, XML_MAPPERS.toArray(new Class<?>[0]));

        for (Class<?> mapper : XML_MAPPERS) {
            assertFalse(mapper.getDeclaredMethods().length == 0, mapper.getSimpleName() + " 没有任何方法");
            for (Method method : mapper.getDeclaredMethods()) {
                String statementId = mapper.getName() + "." + method.getName();
                assertTrue(cfg.hasStatement(statementId),
                        "XML 缺少语句或 id 拼错: " + statementId + "（资源 mapper/"
                                + mapper.getSimpleName() + ".xml）");
            }
        }
    }

    /** 动态表名的两个语句仍由 Provider 提供，不依赖 XML。 */
    @Test
    void detailMapperStillBindsThroughProvider() {
        Configuration cfg = new Configuration();
        cfg.addMapper(DetailMapper.class);
        for (Method method : DetailMapper.class.getDeclaredMethods()) {
            assertTrue(cfg.hasStatement(DetailMapper.class.getName() + "." + method.getName()),
                    "DetailMapper 语句应由 Provider 绑定: " + method.getName());
        }
    }
}
