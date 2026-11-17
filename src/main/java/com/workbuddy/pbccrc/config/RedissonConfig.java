package com.workbuddy.pbccrc.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Redisson 客户端配置。
 *
 * <p>Redis 为必需依赖：应用启动即创建单例 Redisson 客户端，用于提交幂等锁、
 * 解析互斥锁与补偿主节点锁。Redis 不可用时应用启动或相关操作直接失败，不再退化。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
@Configuration
public class RedissonConfig {

    /**
     * 按配置创建单例 Redisson 客户端；配置了口令时才设置密码。
     *
     * @param properties 业务配置
     * @return Redisson 客户端
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(PbccrcProperties properties) {
        PbccrcProperties.Redis redis = properties.getRedis();
        Config config = new Config();
        SingleServerConfig server = config.useSingleServer().setAddress(redis.getAddress());
        // 指定逻辑库序号，与配置文件 pbccrc.redis.database 绑定，避免默认落到 DB0
        server.setDatabase(redis.getDatabase());
        // 密码为空时不要 setPassword("")，否则会握手失败
        if (StringUtils.hasText(redis.getPassword())) {
            server.setPassword(redis.getPassword());
        }
        // Netty 线程数不必等于 CPU 核数，4 个足够支撑万级 QPS 的锁与缓存操作
        config.setThreads(4).setNettyThreads(4);
        return Redisson.create(config);
    }
}
