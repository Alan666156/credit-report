package com.workbuddy.pbccrc.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 异步解析线程池配置。
 *
 * <p>使用 JDK21 虚拟线程执行器，报告量大时无需调整线程池参数。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 构造名为 parseTaskExecutor 的虚拟线程执行器。
     *
     * @return 虚拟线程执行器
     */
    @Bean("parseTaskExecutor")
    public Executor parseTaskExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
