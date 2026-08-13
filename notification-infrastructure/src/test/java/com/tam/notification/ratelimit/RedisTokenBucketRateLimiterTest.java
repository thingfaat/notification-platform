package com.tam.notification.ratelimit;

import com.tam.notification.domain.enums.ChannelType;
import com.tam.notification.domain.ratelimit.RateLimitDecision;
import com.tam.notification.domain.ratelimit.RateLimitReason;
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
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true) // 测试时启动redis
public class RedisTokenBucketRateLimiterTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    @BeforeAll
    static void setUp() {
        connectionFactory = new LettuceConnectionFactory(
                REDIS.getHost(),
                REDIS.getMappedPort(6379)
        );

        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(connectionFactory);

        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void shouldLimitAfterTokenCapacityExhausted() {
        RedisTokenBucketRateLimiter limiter = limiter(
                2,
                1,
                100
        );

        long tenantId = 90001L;

        RateLimitDecision first = limiter.tryAcquire(
                request(
                        tenantId,
                        80001L,
                        ChannelType.SMS,
                        "token-event-1"
                )
        );

        RateLimitDecision second = limiter.tryAcquire(
                request(
                        tenantId,
                        80001L,
                        ChannelType.SMS,
                        "token-event-2"
                )
        );

        RateLimitDecision third = limiter.tryAcquire(
                request(
                        tenantId,
                        80001L,
                        ChannelType.SMS,
                        "token-event-3"
                )
        );

        assertTrue(first.allowed());
        assertTrue(second.allowed());

        assertFalse(third.allowed());
        assertEquals(
                RateLimitReason.TOKEN_BUCKET_EXHAUSTED,
                third.reason()
        );
        assertTrue(third.retryAfterMillis() > 0);
    }

    @Test
    void sameEventShouldNotConsumeTokenOrQuotaTwice() {
        RedisTokenBucketRateLimiter limiter = limiter(
                2,
                1,
                2
        );

        RateLimitRequest request = request(
                90002L,
                80001L,
                ChannelType.SMS,
                "same-event"
        );

        RateLimitDecision first = limiter.tryAcquire(request);

        RateLimitDecision duplicate = limiter.tryAcquire(request);

        assertTrue(first.allowed());
        assertTrue(duplicate.allowed());

        assertEquals(
                first.remainingTokens(),
                duplicate.remainingTokens()
        );

        assertEquals(
                first.remainingDailyQuota(),
                duplicate.remainingDailyQuota()
        );
    }

    @Test
    void shouldRejectAfterDailyQuotaExhausted() {
        RedisTokenBucketRateLimiter limiter = limiter(
                100,
                100,
                2
        );

        long tenantId = 90003L;

        RateLimitDecision first = limiter.tryAcquire(
                request(
                        tenantId,
                        80001L,
                        ChannelType.SMS,
                        "quota-event-1"
                )
        );

        RateLimitDecision second = limiter.tryAcquire(
                request(
                        tenantId,
                        80001L,
                        ChannelType.SMS,
                        "quota-event-2"
                )
        );

        RateLimitDecision third = limiter.tryAcquire(
                request(
                        tenantId,
                        80001L,
                        ChannelType.SMS,
                        "quota-event-3"
                )
        );

        assertTrue(first.allowed());
        assertTrue(second.allowed());

        assertFalse(third.allowed());
        assertEquals(
                RateLimitReason.DAILY_QUOTA_EXHAUSTED,
                third.reason()
        );
        assertEquals(
                0,
                third.remainingDailyQuota()
        );
        assertTrue(third.retryAfterMillis() > 0);
    }

    @Test
    void applicationAndChannelDimensionsShouldBeIsolated() {
        RedisTokenBucketRateLimiter limiter = limiter(
                1,
                1,
                1
        );

        long tenantId = 90004L;

        RateLimitDecision appOneSms = limiter.tryAcquire(
                request(
                        tenantId,
                        80001L,
                        ChannelType.SMS,
                        "dimension-event-1"
                )
        );

        RateLimitDecision appTwoSms = limiter.tryAcquire(
                request(
                        tenantId,
                        80002L,
                        ChannelType.SMS,
                        "dimension-event-2"
                )
        );

        RateLimitDecision appOneEmail = limiter.tryAcquire(
                request(
                        tenantId,
                        80001L,
                        ChannelType.EMAIL,
                        "dimension-event-3"
                )
        );

        RateLimitDecision appOneSmsAgain = limiter.tryAcquire(
                request(
                        tenantId,
                        80001L,
                        ChannelType.SMS,
                        "dimension-event-4"
                )
        );

        assertTrue(appOneSms.allowed());
        assertTrue(appTwoSms.allowed());
        assertTrue(appOneEmail.allowed());

        assertFalse(appOneSmsAgain.allowed());
        assertEquals(
                RateLimitReason.DAILY_QUOTA_EXHAUSTED,
                appOneSmsAgain.reason()
        );
    }

    private RedisTokenBucketRateLimiter limiter(
            long capacity,
            double refillRate,
            long dailyQuota
    ) {
        RateLimitProperties properties =
                new RateLimitProperties();

        properties.setCapacity(capacity);
        properties.setRefillTokensPerSecond(refillRate);
        properties.setBucketTtl(
                Duration.ofMinutes(1)
        );
        properties.setDecisionTtl(
                Duration.ofMinutes(5)
        );
        properties.setDailyQuota(dailyQuota);
        properties.setQuotaZoneId(
                ZoneId.of("Asia/Shanghai")
        );

        return new RedisTokenBucketRateLimiter(
                redisTemplate,
                properties
        );
    }

    private RateLimitRequest request(
            long tenantId,
            long applicationId,
            ChannelType channelType,
            String eventId
    ) {
        return RateLimitRequest.oneToken(
                eventId,
                tenantId,
                applicationId,
                channelType
        );
    }
}
