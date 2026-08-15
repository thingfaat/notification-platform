package com.tam.notification.shortlink.shortlink;

import com.tam.notification.shortlink.algorithm.Base62RandomShortCodeGenerator;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ShortCodeGeneratorExperimentTest {
    /**
     * 随机生成 1,000,000 个 8 位的 base62 码，并观察碰撞概率。
     */
    @Test
    void shouldObserveOneMillionRandomBase62Codes() {
        Base62RandomShortCodeGenerator generator = new Base62RandomShortCodeGenerator();

        int sampleSize = 1_000_000;
        // 预分配足够容量以减少 HashSet 扩容开销
        Set<String> uniqueCodes = new HashSet<>(1_400_000);
        int collisions = 0;

        // 逐次生成短码，校验格式并统计碰撞次数
        for (int index = 0; index < sampleSize; index++) {
            String code = generator.generate();

            assertEquals(8, code.length());
            assertTrue(code.matches("[0-9a-zA-Z]{8}"));

            if (!uniqueCodes.add(code)) {
                collisions++;
            }
        }

        // 输出实验统计结果
        System.out.printf(
                "randomBase62 sample=%d, unique=%d, collisions=%d%n",
                sampleSize,
                uniqueCodes.size(),
                collisions
        );

        // 随机实验只记录实际碰撞数，不把"零碰撞"写成必然断言。
        assertEquals(sampleSize, uniqueCodes.size() + collisions);
    }

    /**
     * 基于生日悖论公式，验证 Base62 短码在不同码长下的理论碰撞概率。
     * 分别计算码长为 8 和 10、样本量为 100 万时的碰撞概率，
     * 并断言结果与预期值一致（精度 1.0e-12）。
     */
    @Test
    void shouldCalculateBirthdayCollisionProbability() {
        // 计算 100 万样本下，8 位和 10 位 Base62 码的理论碰撞概率
        double oneMillionEight = probability(8, 1_000_000L);
        double oneMillionTen = probability(10, 1_000_000L);

        // 8 位码碰撞概率约 0.23%，10 位码约 0.00006%，验证码长越长碰撞风险越低
        assertEquals(
                0.002287382958,
                oneMillionEight,
                1.0e-12
        );
        assertEquals(
                0.000000595734,
                oneMillionTen,
                1.0e-12
        );
    }

    /**
     * 基于生日悖论近似公式，计算 Base62 短码在给定样本量下的理论碰撞概率。
     *
     * <p>公式：P ≈ 1 - e^(-n(n-1) / 2N)，其中 N = 62^codeLength，n = sampleSize。</p>
     *
     * @param codeLength 短码的字符位数，决定码空间大小（62^codeLength）
     * @param sampleSize 随机生成的样本数量
     * @return 碰撞概率，范围 [0, 1]
     */
    private double probability(int codeLength, long sampleSize) {
        // 计算 Base62 码空间总大小
        double space = Math.pow(62.0, codeLength);
        // 计算生日悖论公式的指数部分：-n(n-1) / 2N
        double exponent = -(
                (double) sampleSize * (sampleSize - 1L)
                        / (2.0 * space)
        );

        // -expm1(x) 在概率很小时比 1-exp(x) 更稳定。
        return -Math.expm1(exponent);
    }
}
