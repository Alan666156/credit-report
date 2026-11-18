package com.workbuddy.pbccrc.dao;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * 测试辅助：把 {@code resources/mapper} 下的 Mapper XML 装入手工构建的 {@link Configuration}。
 *
 * <p>生产环境由 mybatis-spring 按 {@code mybatis.mapper-locations} 自动装配；但本目录下的
 * 单测绕开 Spring 直接 {@code new Configuration()}，只 {@code addMapper(接口)} 不会读取 XML，
 * 于是所有语句都会以「Invalid bound statement」失败。统一走本工具，避免每个测试各写一遍。</p>
 *
 * <p>顺带承担一个副作用保护：XML 缺失或语法错误会在测试启动时立刻抛错，而不是等到
 * 上线首个请求才暴露。</p>
 *
 * @author Alan
 * @date 2026-11-18
 */
public final class MapperXml {

    private MapperXml() {
    }

    /**
     * 构建与生产口径一致、并已装载 XML 的 {@link Configuration}。
     *
     * <p>手工构建的 Configuration 默认值与 application.yml 不同，直接用来跑测试会测出
     * 「不是线上口径」的结论：</p>
     * <ul>
     *   <li>{@code mapUnderscoreToCamelCase} 默认 false —— 下划线列会静默映射成 null，
     *       {@code SELECT customer_name ...} 查出来 {@code customerName} 是空的；</li>
     *   <li>{@code jdbcTypeForNull} 默认 OTHER —— {@code error_msg = #{errorMsg}} 写 NULL 时
     *       与生产（yml 配的 {@code 'null'}）行为不一致；</li>
     *   <li>{@code callSettersOnNulls} 默认 false —— 显式置空的字段在实体上保持旧值。</li>
     * </ul>
     *
     * <p>三项分散写在各个 smoke 里极易漏配（{@code SubmitRealRedisSmoke} 就曾漏掉第一项，
     * 只因它恰好没有 VO 断言才没暴露），故集中到此处作为唯一口径来源。</p>
     *
     * @param env    数据源环境
     * @param mappers 需要装载 XML 的 Mapper 接口
     * @return 可直接用于 {@code SqlSessionFactoryBuilder.build} 的 Configuration
     */
    public static Configuration productionLike(Environment env, Class<?>... mappers) {
        Configuration cfg = new Configuration(env);
        cfg.setMapUnderscoreToCamelCase(true);
        cfg.setJdbcTypeForNull(JdbcType.NULL);
        cfg.setCallSettersOnNulls(true);
        load(cfg, mappers);
        return cfg;
    }

    /**
     * 按 Mapper 接口名加载同名 XML（约定路径 {@code mapper/<简单类名>.xml}）。
     *
     * @param cfg    MyBatis 配置
     * @param mappers 需要加载 SQL 的 Mapper 接口
     * @throws UncheckedIOException 资源缺失或读取失败时抛出
     */
    public static void load(Configuration cfg, Class<?>... mappers) {
        for (Class<?> mapper : mappers) {
            String resource = "mapper/" + mapper.getSimpleName() + ".xml";
            try (InputStream in = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(in, cfg, resource, cfg.getSqlFragments()).parse();
            } catch (IOException e) {
                throw new UncheckedIOException("无法加载 Mapper XML: " + resource, e);
            }
        }
    }
}
