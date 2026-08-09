package com.tam.notification.shortlink;

import com.tam.notification.shortlink.algorithm.Base62RandomShortCodeGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class Base62RandomShortCodeGeneratorTest {

    private final Base62RandomShortCodeGenerator generator = new Base62RandomShortCodeGenerator();

    @Test
    void shouldGenerateEightCharacterBase62Code() {
        for (var i = 0; i < 1000; i++) {
            final var code = generator.generate();
            assertEquals(8, code.length());
            assertTrue(code.matches("[0-9a-zA-Z]{8}"));
        }
    }
}
