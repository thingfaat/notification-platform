package com.tam.notification.shortlink;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BloomSliceWindowTest {

    @Test
    void shouldAlignToUtcSliceBoundaryAndReturnHistory() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-08-16T11:59:59Z"),
                ZoneOffset.UTC
        );
        BloomSliceWindow window = new BloomSliceWindow(
                clock,
                Duration.ofHours(6),
                4
        );

        long current = Instant.parse("2026-08-16T06:00:00Z").getEpochSecond();
        assertEquals(current, window.currentSliceStart());
        assertEquals(List.of(
                current,
                current - Duration.ofHours(6).getSeconds(),
                current - Duration.ofHours(12).getSeconds(),
                current - Duration.ofHours(18).getSeconds()
        ), window.retainedSliceStarts());
        assertEquals(Duration.ofHours(30), window.bitmapTtl());
    }
}
