package com.tam.notification.observability;

import com.tam.notification.config.ServerSchedulingConfig;
import com.tam.notification.domain.observability.OutboxBacklogSnapshot;
import com.tam.notification.domain.observability.OutboxObservabilityRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Outbox 监控采样器。
 *
 * <p>数据库查询由定时任务执行，Prometheus 抓取只读取内存，
 * 避免抓取频率直接放大数据库压力。</p>
 */
@Slf4j
@Component
public class OutboxMetrics {

    private final OutboxObservabilityRepository repository;
    private final Clock clock;

    // Gauge 使用弱引用，因此这些状态必须保存在字段中，不能只建局部变量。
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong dead = new AtomicLong();
    private final AtomicLong oldestPendingAgeSeconds = new AtomicLong();
    private final AtomicLong refreshSuccess = new AtomicLong();
    private final AtomicLong lastSuccessEpochSeconds = new AtomicLong();

    @Autowired
    public OutboxMetrics(
            OutboxObservabilityRepository repository,
            MeterRegistry meterRegistry
    ) {
        this(repository, meterRegistry, Clock.systemUTC());
    }

    // 包级构造器允许测试注入固定时钟。
    OutboxMetrics(
            OutboxObservabilityRepository repository,
            MeterRegistry meterRegistry,
            Clock clock
    ) {
        this.repository = repository;
        this.clock = clock;

        Gauge.builder("notification.outbox.backlog", pending, AtomicLong::get)
                .description("未完成的 Outbox 事件数")
                .tag("status", "pending")
                .register(meterRegistry);

        Gauge.builder("notification.outbox.backlog", dead, AtomicLong::get)
                .description("进入 DEAD 的 Outbox 事件数")
                .tag("status", "dead")
                .register(meterRegistry);

        Gauge.builder(
                        "notification.outbox.oldest.pending.age.seconds",
                        oldestPendingAgeSeconds,
                        AtomicLong::get
                )
                .description("最老未完成 Outbox 事件年龄，单位秒")
                .register(meterRegistry);

        Gauge.builder(
                        "notification.outbox.metrics.refresh.success",
                        refreshSuccess,
                        AtomicLong::get
                )
                .description("最近一次 Outbox 指标刷新是否成功，1成功0失败")
                .register(meterRegistry);

        Gauge.builder(
                        "notification.outbox.metrics.last.success.age.seconds",
                        lastSuccessEpochSeconds,
                        this::lastSuccessAgeSeconds
                )
                .description("距离上次成功刷新 Outbox 指标的秒数，未成功过时为-1")
                .register(meterRegistry);
    }

    @Scheduled(
            fixedDelayString = "${notification.observability.outbox.refresh-interval-ms:5000}",
            scheduler = ServerSchedulingConfig.OBSERVABILITY_TASK_SCHEDULER
    )
    public void refresh() {
        try {
            OutboxBacklogSnapshot snapshot = repository.loadSnapshot();
            pending.set(snapshot.pendingCount());
            dead.set(snapshot.deadCount());
            oldestPendingAgeSeconds.set(snapshot.oldestPendingAgeSeconds());
            lastSuccessEpochSeconds.set(clock.instant().getEpochSecond());
            refreshSuccess.set(1);
        } catch (RuntimeException exception) {
            // 保留上一份业务快照，同时用 freshness 指标明确告诉监控“数据已过期”。
            refreshSuccess.set(0);
            log.error("refresh outbox metrics failed", exception);
        }
    }

    private double lastSuccessAgeSeconds(AtomicLong lastSuccess) {
        long epochSeconds = lastSuccess.get();
        if (epochSeconds == 0) {
            return -1;
        }
        return Math.max(0, clock.instant().getEpochSecond() - epochSeconds);
    }
}
