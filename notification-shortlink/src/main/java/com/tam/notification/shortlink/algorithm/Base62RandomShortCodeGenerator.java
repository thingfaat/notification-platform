package com.tam.notification.shortlink.algorithm;

import com.tam.notification.domain.shortlink.ShortCodeGenerator;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * 生产默认的8位随机base62短码生成器
 * 随机生成的不能cong0教学上保证零碰撞，因此最终还是依赖 short_link_mapping0.short_code 唯一索引 + ShortLinkService 有限重试
 */
@Primary // 在多个接口继承到ShortCodeGenerator 时，优先使用本类
@Component
public class Base62RandomShortCodeGenerator implements ShortCodeGenerator {

    private static final char[] BASE62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    private static final int CODE_LENGTH = 8;

    // 使用 SecureRandom，避免普通 Random 的可预测序列
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
