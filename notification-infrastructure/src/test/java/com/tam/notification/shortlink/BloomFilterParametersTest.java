package com.tam.notification.shortlink;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BloomFilterParametersTest {

    @Test
    void shouldCalculateParametersFromOverallProbability() {
        BloomFilterParameters parameters =
                BloomFilterParameters.calculate(100_000, 0.01, 4);

        assertEquals(1_246_262L, parameters.bitSize());
        assertEquals(9, parameters.hashFunctions());
        assertEquals(0.002509430066318874,
                parameters.perSliceFalsePositiveProbability(), 1.0E-15);

        double reconstructedOverall = 1.0 - Math.pow(
                1.0 - parameters.perSliceFalsePositiveProbability(),
                4
        );
        assertEquals(0.01, reconstructedOverall, 1.0E-12);
    }

    @Test
    void shouldRejectInvalidInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> BloomFilterParameters.calculate(0, 0.01, 4));
        assertThrows(IllegalArgumentException.class,
                () -> BloomFilterParameters.calculate(100, 1.0, 4));
        assertThrows(IllegalArgumentException.class,
                () -> BloomFilterParameters.calculate(100, 0.01, 0));
    }
}
