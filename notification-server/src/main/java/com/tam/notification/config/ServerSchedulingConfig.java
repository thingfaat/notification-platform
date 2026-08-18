package com.tam.notification.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Server 定时任务线程隔离配置。
 *
 * <p>Outbox 发布、监控采样和短链 Bloom 维护分别使用独立线程池，
 * 避免 Redis、MySQL 故障时的连接等待阻塞消息发布。</p>
 */
@Configuration
public class ServerSchedulingConfig {
    public static final String OUTBOX_TASK_SCHEDULER = "outboxTaskScheduler";
    public static final String OBSERVABILITY_TASK_SCHEDULER =
            "observabilityTaskScheduler";
    public static final String SHORT_LINK_MAINTENANCE_TASK_SCHEDULER =
            "shortLinkMaintenanceTaskScheduler";

    @Bean(name = OUTBOX_TASK_SCHEDULER)
    public ThreadPoolTaskScheduler outboxTaskScheduler() {
        // Outbox 单实例内按批次串行发布，独立线程保证不被其他定时任务占用。
        return createScheduler(1, "outbox-");
    }

    @Bean(name = OBSERVABILITY_TASK_SCHEDULER)
    public ThreadPoolTaskScheduler observabilityTaskScheduler() {
        // Outbox 和 Bloom 两类采样可以并行，某个依赖超时不会阻塞另一类指标。
        return createScheduler(2, "observability-");
    }

    @Bean(name = SHORT_LINK_MAINTENANCE_TASK_SCHEDULER)
    public ThreadPoolTaskScheduler shortLinkMaintenanceTaskScheduler() {
        // Bloom 重建自身已有 CAS 防重，一条维护线程即可。
        return createScheduler(1, "short-link-maintenance-");
    }

    private ThreadPoolTaskScheduler createScheduler(
            int poolSize,
            String threadNamePrefix
    ) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }
}
