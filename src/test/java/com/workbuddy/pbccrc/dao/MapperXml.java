package com.workbuddy.pbccrc.dao;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;

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
final class MapperXml {

    private MapperXml() {
    }

    /**
     * 按 Mapper 接口名加载同名 XML（约定路径 {@code mapper/<简单类名>.xml}）。
     *
     * @param cfg    MyBatis 配置
     * @param mappers 需要加载 SQL 的 Mapper 接口
     * @throws UncheckedIOException 资源缺失或读取失败时抛出
     */
    static void load(Configuration cfg, Class<?>... mappers) {
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
