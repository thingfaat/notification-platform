package com.tam.notification.resilience;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 渠道服务熔断弹性配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "notification.channel-resilience")
public class ChannelResilienceProperties {

    private int corePoolSize = 2; // 线程池核心线程数

    private int maxPoolSize = 4; // 线程池最大线程数

    private int queueCapacity = 100; // 线程池队列容量

    private Duration keepAlive = Duration.ofSeconds(30); // 线程池空闲线程存活时间

    private Duration callTimeout = Duration.ofSeconds(2); // 调用超时时间

    private int failureThreshold = 3; // 熔断失败阈值

    private Duration openDuration = Duration.ofSeconds(10); // 熔断打开时间
}
