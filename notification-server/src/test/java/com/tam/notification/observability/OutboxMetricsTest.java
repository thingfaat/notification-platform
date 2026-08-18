package com.tam.notification.observability;

import com.tam.notification.domain.observability.OutboxBacklogSnapshot;
import com.tam.notification.domain.observability.OutboxObservabilityRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxMetricsTest {

    @Test
    void shouldPublishSnapshotAndFreshness() {
        OutboxObservabilityRepository repository =
                mock(OutboxObservabilityRepository.class);
        when(repository.loadSnapshot())
                .thenReturn(new OutboxBacklogSnapshot(12, 2, 45));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Clock clock = Clock.fixed(
                Instant.parse("2026-08-17T10:00:00Z"),
                ZoneOffset.UTC
        );

        OutboxMetrics metrics = new OutboxMetrics(
                repository,
                registry,
                clock
        );
        metrics.refresh();

        assertThat(registry.get("notification.outbox.backlog")
                .tag("status", "pending")
                .gauge()
                .value()).isEqualTo(12);
        assertThat(registry.get("notification.outbox.backlog")
                .tag("status", "dead")
                .gauge()
                .value()).isEqualTo(2);
        assertThat(registry.get("notification.outbox.oldest.pending.age.seconds")
                .gauge()
                .value()).isEqualTo(45);
        assertThat(registry.get("notification.outbox.metrics.refresh.success")
                .gauge()
                .value()).isEqualTo(1);
        assertThat(registry.get("notification.outbox.metrics.last.success.age.seconds")
                .gauge()
                .value()).isZero();
    }

    @Test
    void shouldMarkRefreshFailedWithoutReplacingLastSnapshot() {
        OutboxObservabilityRepository repository =
                mock(OutboxObservabilityRepository.class);
        when(repository.loadSnapshot())
                .thenReturn(new OutboxBacklogSnapshot(7, 0, 10))
                .thenThrow(new IllegalStateException("mysql down"));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxMetrics metrics = new OutboxMetrics(
                repository,
                registry,
                Clock.systemUTC()
        );

        metrics.refresh();
        metrics.refresh();

        assertThat(registry.get("notification.outbox.backlog")
                .tag("status", "pending")
                .gauge()
                .value()).isEqualTo(7);
        assertThat(registry.get("notification.outbox.metrics.refresh.success")
                .gauge()
                .value()).isZero();
    }
}
