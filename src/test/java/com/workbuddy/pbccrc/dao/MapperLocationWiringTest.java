package com.workbuddy.pbccrc.dao;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code mybatis.mapper-locations} 的装配契约。
 *
 * <p>SQL 迁入 XML 后，生产能否取到语句完全取决于 application.yml 里那一行
 * {@code classpath*:mapper/**&#47;*.xml}。它是字符串，写错不报编译错，只在上线后
 * 以 {@code Invalid bound statement} 的形式炸在首个请求上。{@link MapperXmlLoadTest}
 * 用的是约定的固定路径 {@code mapper/<接口名>.xml}，绕开了这行配置，因此守不住它。</p>
 *
 * <p>本测试直接读 application.yml 的原值，用 Spring 的同一个解析器
 * （{@link PathMatchingResourcePatternResolver}）去匹配，断言：</p>
 * <ol>
 *   <li>pattern 至少能匹配到全部 6 个 XML，一个不落；</li>
 *   <li>这些资源装进同一个 {@link Configuration} 后，6 个 Mapper 接口的每个方法都能找到 statement；</li>
 *   <li>{@code DetailMapper} 不在其中——它的语句由 Provider 生成，不该出现在 XML 里。</li>
 * </ol>
 *
 * @author Alan
 * @date 2026-11-18
 */
class MapperLocationWiringTest {

    /** 走 XML 的 Mapper，与 {@link MapperXmlLoadTest} 保持一致。 */
    private static final List<Class<?>> XML_MAPPERS = List.of(
            ReportMapper.class,
            ReportContentMapper.class,
            ReportQueryLogMapper.class,
            DetailQueryMapper.class,
            RuleMapper.class,
            CustomerHistoryMapper.class
    );

    @Test
    @SuppressWarnings("unchecked")
    void mapperLocationsResolvesEveryXmlMapper() throws Exception {
        String pattern = mapperLocationsFromApplicationYml();
        assertNotNull(pattern, "application.yml 缺少 mybatis.mapper-locations");
        assertTrue(pattern.contains("mapper"), "mapper-locations 应指向 mapper 目录，实际=" + pattern);

        Resource[] resources = new PathMatchingResourcePatternResolver().getResources(pattern);
        assertEquals(XML_MAPPERS.size(), resources.length,
                "mapper-locations 匹配到的 XML 数量与 Mapper 接口数不一致，实际匹配=" + resources.length);

        Configuration cfg = new Configuration();
        for (Resource resource : resources) {
            try (InputStream in = resource.getInputStream()) {
                new XMLMapperBuilder(in, cfg, resource.getFilename(), cfg.getSqlFragments()).parse();
            }
        }

        for (Class<?> mapper : XML_MAPPERS) {
            for (Method method : mapper.getDeclaredMethods()) {
                assertTrue(cfg.hasStatement(mapper.getName() + "." + method.getName()),
                        "mapper-locations 未装载语句: " + mapper.getSimpleName() + "." + method.getName());
            }
        }
    }

    /** 原样读取 application.yml 里的 mybatis.mapper-locations，不做任何替换。 */
    @SuppressWarnings("unchecked")
    private static String mapperLocationsFromApplicationYml() {
        try (InputStream in = MapperLocationWiringTest.class.getResourceAsStream("/application.yml")) {
            assertNotNull(in, "classpath 下找不到 application.yml");
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> mybatis = (Map<String, Object>) root.get("mybatis");
            assertNotNull(mybatis, "application.yml 缺少 mybatis 配置段");
            return (String) mybatis.get("mapper-locations");
        } catch (Exception e) {
            throw new IllegalStateException("读取 application.yml 失败", e);
        }
    }
}
