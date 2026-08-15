package com.tam.notification.shortlink.algorithm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class Base62CodecTest {

    /**
     * 测试无损base62编解码
     */
    @Test
    void shouldRoundTripRepresentativeLongValues() {
        long[] values = {
                0L,
                1L,
                61L,
                62L,
                63L,
                1_000_000L,
                Long.MAX_VALUE
        };

        for (long value : values) {
            String encoded = Base62Codec.encode(value);
            long decoded = Base62Codec.decode(encoded);

            assertEquals(value, decoded);
        }
    }

    /**
     * 测试正数long的11位编码
     */
    @Test
    void positiveLongShouldNeedAtMostElevenCharacters() {
        String encoded = Base62Codec.encode(Long.MAX_VALUE);

        assertEquals(11, encoded.length());
        assertEquals(Long.MAX_VALUE, Base62Codec.decode(encoded));
    }

    /**
     * 测试非法输入
     */
    @Test
    void shouldRejectNegativeOrInvalidInput() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Base62Codec.encode(-1L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> Base62Codec.decode("abc-123")
        );
    }
}
