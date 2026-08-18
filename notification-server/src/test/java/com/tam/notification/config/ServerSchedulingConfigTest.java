package com.tam.notification.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;

class ServerSchedulingConfigTest {

    @Test
    void shouldCreateIndependentSchedulersWithExpectedPoolSizes() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(ServerSchedulingConfig.class)) {
            ThreadPoolTaskScheduler outbox = context.getBean(
                    ServerSchedulingConfig.OUTBOX_TASK_SCHEDULER,
                    ThreadPoolTaskScheduler.class
            );
            ThreadPoolTaskScheduler observability = context.getBean(
                    ServerSchedulingConfig.OBSERVABILITY_TASK_SCHEDULER,
                    ThreadPoolTaskScheduler.class
            );
            ThreadPoolTaskScheduler shortLinkMaintenance = context.getBean(
                    ServerSchedulingConfig.SHORT_LINK_MAINTENANCE_TASK_SCHEDULER,
                    ThreadPoolTaskScheduler.class
            );

            // 三个 Bean 必须相互独立，避免发布、维护和监控任务争用线程。
            assertThat(outbox).isNotSameAs(observability);
            assertThat(outbox).isNotSameAs(shortLinkMaintenance);
            assertThat(observability).isNotSameAs(shortLinkMaintenance);
            assertThat(outbox.getScheduledThreadPoolExecutor().getCorePoolSize())
                    .isEqualTo(1);
            assertThat(observability.getScheduledThreadPoolExecutor().getCorePoolSize())
                    .isEqualTo(2);
            assertThat(shortLinkMaintenance.getScheduledThreadPoolExecutor().getCorePoolSize())
                    .isEqualTo(1);
        }
    }
}
