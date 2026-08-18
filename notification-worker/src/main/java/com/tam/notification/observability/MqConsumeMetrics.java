package com.tam.notification.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Worker 业务消费指标。
 *
 * <p>Broker backlog 由 RocketMQ 原生指标负责；这里回答 Worker 收到后
 * 是初次处理还是重试、最终成功还是抛异常。</p>
 */
@Component
public class MqConsumeMetrics {
    private final MeterRegistry meterRegistry;
    private final Counter initialCounter;
    private final Counter retryCounter;
    private final Counter dlqReceivedCounter;
    private final Timer successTimer;
    private final Timer failureTimer;

    public MqConsumeMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        this.initialCounter = Counter.builder("notification.mq.consume")
                .description("Worker 收到的通知消息数")
                .tag("kind", "initial")
                .register(meterRegistry);

        this.retryCounter = Counter.builder("notification.mq.consume")
                .description("Worker 收到的通知消息数")
                .tag("kind", "retry")
                .register(meterRegistry);

        this.dlqReceivedCounter = Counter.builder("notification.mq.dlq.received")
                .description("业务 DLQ Listener 收到的死信数")
                .register(meterRegistry);

        this.successTimer = Timer.builder("notification.mq.consume.duration")
                .description("通知 MQ 消费处理耗时")
                .tag("outcome", "success")
                .publishPercentileHistogram()
                .register(meterRegistry);

        this.failureTimer = Timer.builder("notification.mq.consume.duration")
                .description("通知 MQ 消费处理耗时")
                .tag("outcome", "failure")
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    public Timer.Sample start(int reconsumeTimes) {
        if (reconsumeTimes > 0) {
            retryCounter.increment();
        } else {
            initialCounter.increment();
        }
        return Timer.start(meterRegistry);
    }

    public void recordSuccess(Timer.Sample sample) {
        sample.stop(successTimer);
    }

    public void recordFailure(Timer.Sample sample) {
        sample.stop(failureTimer);
    }

    public void recordDlqReceived() {
        dlqReceivedCounter.increment();
    }
}
