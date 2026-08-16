package com.tam.notification.shortlink;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tam.notification.domain.shortlink.ShortLinkCacheEntry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnabledIfEnvironmentVariable(
        named = "REDIS_CLUSTER_NODES",
        matches = ".+"
)
public class RedisShortLinkClusterIntegrationTest {

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static ObjectMapper objectMapper;

    @BeforeAll
    static void setUp() {
        List<String> nodes = List.of(
                System.getenv("REDIS_CLUSTER_NODES").split(",")
        );

        RedisClusterConfiguration configuration =
                new RedisClusterConfiguration(nodes);

        String password = System.getenv().getOrDefault(
                "REDIS_CLUSTER_PASSWORD",
                "notification123"
        );
        configuration.setPassword(RedisPassword.of(password));
        configuration.setMaxRedirects(5);

        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());
    }

    @AfterAll
    static void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void sameTagShouldSupportMultiGet() {
        String shortCode = "D18SLOT1";
        String redirectKey = ShortLinkRedisKeys.redirect(shortCode);
        String negativeKey = ShortLinkRedisKeys.negative(shortCode);

        try {
            redisTemplate.opsForValue().set(redirectKey, "redirect-value");
            redisTemplate.opsForValue().set(negativeKey, "NOT_FOUND");

            assertEquals(
                    List.of("redirect-value", "NOT_FOUND"),
                    redisTemplate.opsForValue().multiGet(
                            List.of(redirectKey, negativeKey)
                    )
            );
        } finally {
            redisTemplate.delete(List.of(redirectKey, negativeKey));
        }
    }

    @Test
    void batchReaderShouldMergeMultipleSlots() throws Exception {
        String firstCode = "D18BAT01";
        String secondCode = "D18BAT02";

        ShortLinkCacheEntry first = entry(101L, "https://example.com/1");
        ShortLinkCacheEntry second = entry(102L, "https://example.com/2");

        String firstKey = ShortLinkRedisKeys.redirect(firstCode);
        String secondKey = ShortLinkRedisKeys.redirect(secondCode);

        try {
            redisTemplate.opsForValue().set(
                    firstKey,
                    objectMapper.writeValueAsString(first)
            );
            redisTemplate.opsForValue().set(
                    secondKey,
                    objectMapper.writeValueAsString(second)
            );

            RedisShortLinkCache cache = new RedisShortLinkCache(
                    redisTemplate,
                    objectMapper,
                    100,
                    Duration.ofMinutes(1)
            );

            Map<String, ShortLinkCacheEntry> result = cache.getAll(
                    List.of(firstCode, secondCode)
            );

            assertEquals(first, result.get(firstCode));
            assertEquals(second, result.get(secondCode));
        } finally {
            redisTemplate.delete(List.of(firstKey, secondKey));
        }
    }

    private ShortLinkCacheEntry entry(Long id, String url) {
        return new ShortLinkCacheEntry(
                10001L,
                id,
                url,
                LocalDateTime.now().plusMinutes(10)
        );
    }
}
