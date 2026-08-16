package com.tam.notification.shortlink;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.*;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "REDIS_CLUSTER_NODES", matches = ".+")
class RedisTimeSlicedBloomFilterIntegrationTest {

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    @BeforeAll
    static void connectCluster() {
        List<String> nodes = List.of(
                System.getenv("REDIS_CLUSTER_NODES").split(",")
        );
        RedisClusterConfiguration configuration =
                new RedisClusterConfiguration(nodes);
        configuration.setPassword(RedisPassword.of(
                System.getenv().getOrDefault(
                        "REDIS_CLUSTER_PASSWORD",
                        "notification123"
                )
        ));
        configuration.setMaxRedirects(5);

        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @BeforeEach
    void cleanBeforeTest() {
        cleanBloomV3Keys();
    }

    @AfterEach
    void cleanAfterTest() {
        cleanBloomV3Keys();
    }

    @AfterAll
    static void closeClusterConnection() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void rebuildShouldKeepExistingCodesAndFailOpenBeforeReady() {
        MutableClock clock = new MutableClock(
                Instant.parse("2026-08-16T00:00:00Z")
        );
        RedisShortLinkProtection protection = protection(clock, 10_000, 4);

        assertTrue(protection.beginBloomRebuild());

        // begin 已删除 ready，合法短码必须被放行，而不是返回 false。
        assertTrue(protection.mightContain("Ab12Cd34"));

        protection.completeBloomRebuild(List.of("Ab12Cd34", "Ef56Gh78"));
        assertTrue(protection.isBloomReady());
        assertTrue(protection.mightContain("Ab12Cd34"));
        assertTrue(protection.mightContain("Ef56Gh78"));
    }

    @Test
    void rotationShouldQueryHistoryAndDeleteExpiredSlice() {
        MutableClock clock = new MutableClock(
                Instant.parse("2026-08-16T00:00:00Z")
        );
        RedisShortLinkProtection protection = protection(clock, 10_000, 4);
        long firstSlice = clock.instant().getEpochSecond();

        // 连续建立 4 个片；每片放一个固定样本，验证历史片查询。
        for (int slice = 0; slice < 4; slice++) {
            assertTrue(protection.beginBloomRebuild());
            protection.completeBloomRebuild(List.of("slice-code-" + slice));
            if (slice < 3) {
                clock.advance(Duration.ofHours(1));
            }
        }

        assertTrue(protection.mightContain("slice-code-0"));
        assertEquals(
                Boolean.TRUE,
                redisTemplate.hasKey(ShortLinkRedisKeys.bloomSlice(firstSlice))
        );

        // 第 5 片完成后，第 1 片已经落在 4 片窗口之外。
        clock.advance(Duration.ofHours(1));
        assertTrue(protection.beginBloomRebuild());
        protection.completeBloomRebuild(List.of("slice-code-4"));

        assertEquals(
                Boolean.FALSE,
                redisTemplate.hasKey(ShortLinkRedisKeys.bloomSlice(firstSlice))
        );
    }

    @Test
    void measuredFalsePositiveRateShouldStayNearConfiguredTarget() {
        MutableClock clock = new MutableClock(
                Instant.parse("2026-08-16T00:00:00Z")
        );
        RedisShortLinkProtection protection = protection(clock, 2_000, 4);

        // 给 4 个片写入互不相同的样本，覆盖“查询片并集”的误判率。
        for (int slice = 0; slice < 4; slice++) {
            int sliceIndex = slice; // Lambda 只能捕获 effectively-final 变量。
            List<String> codes = IntStream.range(0, 2_000)
                    .mapToObj(index ->
                            "present-" + sliceIndex + "-" + index)
                    .toList();

            assertTrue(protection.beginBloomRebuild());
            protection.completeBloomRebuild(codes);
            if (slice < 3) {
                clock.advance(Duration.ofHours(1));
            }
        }

        int samples = 20_000;
        long falsePositives = IntStream.range(0, samples)
                .filter(index -> protection.mightContain("absent-" + index))
                .count();
        double actualRate = falsePositives / (double) samples;

        System.out.printf(
                "samples=%d, falsePositives=%d, actualRate=%.6f%n",
                samples,
                falsePositives,
                actualRate
        );

        // 小样本允许波动；实际值必须记录，不能只口头声称约 1%。
        assertTrue(
                actualRate <= 0.02,
                "actual false positive rate is too high: " + actualRate
        );
    }

    private RedisShortLinkProtection protection(
            Clock clock,
            long expectedInsertions,
            int retainedSlices
    ) {
        return new RedisShortLinkProtection(
                redisTemplate,
                expectedInsertions,
                0.01,
                Duration.ofHours(1),
                retainedSlices,
                Duration.ofMinutes(2),
                Duration.ZERO,
                clock
        );
    }

    private void cleanBloomV3Keys() {
        String registryKey = ShortLinkRedisKeys.bloomSliceRegistry();
        Set<String> sliceIds = redisTemplate.opsForZSet().range(
                registryKey,
                0,
                -1
        );

        if (sliceIds != null && !sliceIds.isEmpty()) {
            List<String> bitmapKeys = sliceIds.stream()
                    .map(Long::parseLong)
                    .map(ShortLinkRedisKeys::bloomSlice)
                    .toList();
            redisTemplate.delete(bitmapKeys);
        }

        // 所有 Key 共享 {bloom:v3}，一次 DEL 不会产生 CROSSSLOT。
        redisTemplate.delete(List.of(
                ShortLinkRedisKeys.bloomReady(),
                registryKey
        ));
    }

    private static final class MutableClock extends Clock {
        private volatile Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException(
                        "test clock only supports UTC"
                );
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
