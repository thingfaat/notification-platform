package com.tam.notification.observability;

import com.tam.notification.domain.shortlink.ShortLinkProtection;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 定期验证共享 ready、当前时间片与本机 trusted 是否一致。
 */
@Slf4j
@Component
public class ShortLinkBloomMetrics {
    private final ShortLinkProtection shortLinkProtection;
    private final AtomicInteger trusted = new AtomicInteger();

    public ShortLinkBloomMetrics(
            ShortLinkProtection shortLinkProtection,
            MeterRegistry meterRegistry
    ) {
        this.shortLinkProtection = shortLinkProtection;

        Gauge.builder(
                        "notification.shortlink.bloom.trusted",
                        trusted,
                        AtomicInteger::get
                )
                .description("时间分片 Bloom 当前是否可信，1可信0不可信")
                .register(meterRegistry);
    }

    @Scheduled(
            fixedDelayString = "${notification.observability.bloom.refresh-interval-ms:15000}"
    )
    public void refresh() {
        try {
            trusted.set(shortLinkProtection.isBloomReady() ? 1 : 0);
        } catch (RuntimeException exception) {
            // 当前实现通常会在内部吞掉 Redis 异常并返回 false，这里仍做边界兜底。
            trusted.set(0);
            log.error("refresh bloom trusted metric failed", exception);
        }
    }
}
