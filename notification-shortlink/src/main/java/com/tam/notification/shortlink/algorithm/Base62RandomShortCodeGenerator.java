package com.tam.notification.shortlink.algorithm;

import com.tam.notification.domain.shortlink.ShortCodeGenerator;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class Base62RandomShortCodeGenerator implements ShortCodeGenerator {

    private static final char[] BASE62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    private static final int CODE_LENGTH = 8;
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public String generate() {
        char[] result = new char[CODE_LENGTH];

        for (int index = 0; index < CODE_LENGTH; index++) {
            int randomIndex = secureRandom.nextInt(BASE62.length);
            result[index] = BASE62[randomIndex];
        }

        return new String(result);
    }
}
