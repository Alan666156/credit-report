package com.workbuddy.pbccrc;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 人行个人征信报告解析服务启动类。
 *
 * <p>开启组件扫描、MyBatis Mapper 扫描、异步解析与定时任务，作为整个服务的入口。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
@SpringBootApplication
@MapperScan("com.workbuddy.pbccrc.dao")
@EnableAsync
@EnableScheduling
public class PbccrcApplication {

    /**
     * 应用入口方法。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(PbccrcApplication.class, args);
    }
}
