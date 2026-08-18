package com.tam.notification.observability;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MqConsumeMetricsTest {

    @Test
    void shouldSeparateInitialRetryFailureAndDlq() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MqConsumeMetrics metrics = new MqConsumeMetrics(registry);

        Timer.Sample initial = metrics.start(0);
        metrics.recordSuccess(initial);

        Timer.Sample retry = metrics.start(2);
        metrics.recordFailure(retry);

        metrics.recordDlqReceived();

        assertThat(registry.get("notification.mq.consume")
                .tag("kind", "initial")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("notification.mq.consume")
                .tag("kind", "retry")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("notification.mq.consume.duration")
                .tag("outcome", "success")
                .timer().count()).isEqualTo(1);
        assertThat(registry.get("notification.mq.consume.duration")
                .tag("outcome", "failure")
                .timer().count()).isEqualTo(1);
        assertThat(registry.get("notification.mq.dlq.received")
                .counter().count()).isEqualTo(1);
    }
}
