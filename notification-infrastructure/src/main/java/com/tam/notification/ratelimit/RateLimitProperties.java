package com.tam.notification.ratelimit;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZoneId;

@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "notification.rate-limit")
public class RateLimitProperties {
    /**
     * 桶容量，允许的瞬时突发量
     */
    private long capacity = 10;

    /**
     * 每秒补充令牌
     */
    private double refillTokensPerSecond = 5.0;

    /**
     * bucket长时间不使用后的过期时间
     */
    private Duration bucketTtl = Duration.ofMinutes(10);

    /**
     * 同一eventId判定结果的缓存时间，应覆盖rocket mq的最大重投周期
     */
    private Duration decisionTtl = Duration.ofHours(1);

    /**
     * 每个租户、应用、渠道组合每天允许发送的数量
     */
    private long dailyQuota = 100000;

    /**
     * 日配额自然日所属时区
     */
    private ZoneId quotaZoneId = ZoneId.of("Asia/Shanghai");
}
