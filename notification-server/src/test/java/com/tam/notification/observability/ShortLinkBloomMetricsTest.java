package com.tam.notification.observability;

import com.tam.notification.domain.shortlink.ShortLinkProtection;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShortLinkBloomMetricsTest {

    @Test
    void shouldExposeTrustedState() {
        ShortLinkProtection protection = mock(ShortLinkProtection.class);
        when(protection.isBloomReady()).thenReturn(true, false);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ShortLinkBloomMetrics metrics = new ShortLinkBloomMetrics(
                protection,
                registry
        );

        metrics.refresh();
        assertThat(registry.get("notification.shortlink.bloom.trusted")
                .gauge().value()).isEqualTo(1);

        metrics.refresh();
        assertThat(registry.get("notification.shortlink.bloom.trusted")
                .gauge().value()).isZero();
    }
}
