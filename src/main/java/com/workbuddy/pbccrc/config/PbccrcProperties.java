package com.workbuddy.pbccrc.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 业务配置绑定，前缀为 {@code pbccrc}。
 *
 * <p>包含 Redis 幂等开关与参数、解析状态机相关参数；各值为默认配置，可由配置文件覆盖。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
@Data
@Component
@ConfigurationProperties(prefix = "pbccrc")
public class PbccrcProperties {
    private Redis redis = new Redis();
    private Parse parse = new Parse();
    private Security security = new Security();

    /**
     * Redis 配置：幂等锁与分布式锁参数（Redis 为必需依赖，无开关）。
     */
    @Data
    public static class Redis {
        private String address = "redis://127.0.0.1:6379";
        private String password;
        /** Redis 逻辑库序号，对应 Redisson 的 database，隔离不同环境/用途的键。 */
        private int database = 0;
        private int submitLockTtlDays = 7;
    }

    /**
     * 解析状态机配置：重试、心跳与补偿扫描参数。
     */
    @Data
    public static class Parse {
        private int maxRetry = 5;
        private int heartbeatTimeoutMinutes = 30;
        /** 单次解析硬超时（分钟）：看门狗据此判定卡死，避免只在阶段推进时才刷心跳导致漏检。 */
        private int parseTimeoutMinutes = 20;
        private String deadLetterScanCron = "0 */5 * * * *";
    }

    /**
     * 管理端最小鉴权配置（评审 2.5）：默认关闭，生产环境应开启并配置固定 token。
     */
    @Data
    public static class Security {
        /** 是否开启鉴权，默认关闭以兼容现有本地/测试调用。 */
        private boolean enabled = false;
        /** 固定访问 token，通过请求头 X-Auth-Token 传入。 */
        private String token = "";
        /** 操作人请求头名，鉴权通过后作为可信操作人写入上下文。 */
        private String operatorHeader = "X-Operator";
    }
}
