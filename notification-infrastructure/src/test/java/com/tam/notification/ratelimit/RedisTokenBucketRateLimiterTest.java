package com.tam.notification.ratelimit;

import com.tam.notification.domain.enums.ChannelType;
import com.tam.notification.domain.ratelimit.RateLimitDecision;
import com.tam.notification.domain.ratelimit.RateLimitRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true) // 测试时启动redis
public class RedisTokenBucketRateLimiterTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7")
    ).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static RedisTokenBucketRateLimiter rateLimiter;

    @BeforeAll
    static void setUp() {
        connectionFactory = new LettuceConnectionFactory(
                REDIS.getHost(),
                REDIS.getMappedPort(6379)
        );

        connectionFactory.afterPropertiesSet();

        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);

        redisTemplate.afterPropertiesSet();

        RateLimitProperties properties = new RateLimitProperties();
        properties.setCapacity(2);
        properties.setRefillTokensPerSecond(1);
        properties.setBucketTtl(Duration.ofMinutes(1));
        properties.setDecisionTtl(Duration.ofMinutes(5));

        rateLimiter = new RedisTokenBucketRateLimiter(
                redisTemplate,
                properties
        );
    }

    @AfterAll
    static void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void shouldLimitAfterCapacityExhausted() {
        final var tenantId = 90001L;
        RateLimitDecision first = rateLimiter.tryAcquire(request(tenantId, "event-1"));
        RateLimitDecision second = rateLimiter.tryAcquire(request(tenantId, "event-2"));
        RateLimitDecision third = rateLimiter.tryAcquire(request(tenantId, "event-3"));

        assertTrue(first.allowed());
        assertTrue(second.allowed());
        assertFalse(third.allowed());
        assertTrue(third.retryAfterMillis() > 0);
    }

    @Test
    void sameEventShouldNotConsumeTokenTwice() {
        final var tenantId = 90002L;
        RateLimitDecision first = rateLimiter.tryAcquire(request(tenantId, "same-event"));
        RateLimitDecision duplicate = rateLimiter.tryAcquire(request(tenantId, "same-event"));

        assertTrue(first.allowed());
        assertTrue(duplicate.allowed());
    }

    private static RateLimitRequest request(long tenantId, String eventId) {
        return RateLimitRequest.oneToken(
                eventId,
                tenantId,
                80001L,
                ChannelType.SMS
        );
    }
}
