package com.tam.notification.shortlink.idempotency;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ShortLinkIdempotencyKeysTest {
    @Test
    void shouldNormalizeManagementRequestId() {
        assertEquals(
                "req-001",
                ShortLinkIdempotencyKeys.management("  req-001  ")
        );
    }

    @Test
    void shouldBuildStableMessageTrackingKey() {
        String first = ShortLinkIdempotencyKeys.messageTracking(
                90001L,
                "https://example.com/orders/100"
        );
        String repeated = ShortLinkIdempotencyKeys.messageTracking(
                90001L,
                "  https://example.com/orders/100  "
        );

        assertEquals(first, repeated);
    }

    @Test
    void shouldNotMergeDifferentMessagesOrTargets() {
        String base = ShortLinkIdempotencyKeys.messageTracking(
                90001L,
                "https://example.com/orders/100"
        );

        String anotherMessage = ShortLinkIdempotencyKeys.messageTracking(
                90002L,
                "https://example.com/orders/100"
        );

        String anotherTarget = ShortLinkIdempotencyKeys.messageTracking(
                90001L,
                "https://example.com/orders/100/refund"
        );

        assertNotEquals(base, anotherMessage);
        assertNotEquals(base, anotherTarget);
    }

    @Test
    void shouldRejectInvalidInput() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ShortLinkIdempotencyKeys.management(" ")
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> ShortLinkIdempotencyKeys.messageTracking(
                        null,
                        "https://example.com"
                )
        );
    }
}
