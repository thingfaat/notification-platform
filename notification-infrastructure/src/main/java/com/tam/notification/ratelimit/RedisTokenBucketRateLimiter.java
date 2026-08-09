package com.tam.notification.ratelimit;

import com.tam.notification.domain.ratelimit.RateLimitDecision;
import com.tam.notification.domain.ratelimit.RateLimitRequest;
import com.tam.notification.domain.ratelimit.RateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisTokenBucketRateLimiter implements RateLimiter {

    /**
     * 读取令牌
     * 补充令牌
     * 判断
     * 扣减
     * 缓存eventId判定
     */
    private final static String LUA_SCRIPT = """
            local bucketKey = KEYS[1]
            local decisionKey = KEYS[2]
            
            local capacity = tonumber(ARGV[1])
            local refillRate = tonumber(ARGV[2])
            local requested = tonumber(ARGV[3])
            local bucketTtl = tonumber(ARGV[4])
            local decisionTtl = tonumber(ARGV[5])
            
            -- 同一个eventId重复执行时，复用第一次限流结果
            local cachedAllowed =
                redis.call('HGET', decisionKey, 'allowed')
            
            if cachedAllowed then
                local cachedRetry =
                    redis.call('HGET', decisionKey, 'retry_after')
                local cachedRemaining =
                    redis.call('HGET', decisionKey, 'remaining')
            
                return {
                    tonumber(cachedAllowed),
                    tonumber(cachedRetry or '0'),
                    tonumber(cachedRemaining or '0')
                }
            end
            
            -- 使用Redis服务端时间，避免多个Worker机器时钟不一致
            local redisTime = redis.call('TIME')
            local now =
                tonumber(redisTime[1]) * 1000
                + math.floor(tonumber(redisTime[2]) / 1000)
            
            local state = redis.call(
                'HMGET',
                bucketKey,
                'tokens',
                'last_refill'
            )
            
            local tokens = tonumber(state[1])
            local lastRefill = tonumber(state[2])
            
            if tokens == nil then
                tokens = capacity
            end
            
            if lastRefill == nil then
                lastRefill = now
            end
            
            local elapsed = math.max(0, now - lastRefill)
            
            tokens = math.min(
                capacity,
                tokens + elapsed * refillRate / 1000
            )
            
            local allowed = 0
            local retryAfter = 0
            
            if tokens >= requested then
                tokens = tokens - requested
                allowed = 1
            else
                retryAfter = math.ceil(
                    (requested - tokens)
                    * 1000
                    / refillRate
                )
            end
            
            local remaining = math.floor(tokens)
            
            redis.call(
                'HSET',
                bucketKey,
                'tokens',
                tostring(tokens),
                'last_refill',
                tostring(now)
            )
            
            redis.call('PEXPIRE', bucketKey, bucketTtl)
            
            redis.call(
                'HSET',
                decisionKey,
                'allowed',
                tostring(allowed),
                'retry_after',
                tostring(retryAfter),
                'remaining',
                tostring(remaining)
            )
            
            redis.call(
                'PEXPIRE',
                decisionKey,
                decisionTtl
            )
            
            return {
                allowed,
                retryAfter,
                remaining
            }
            """;

    private final static RedisScript<List> SCRIPT = new DefaultRedisScript<>(
            LUA_SCRIPT,
            List.class
    );

    private final StringRedisTemplate redisTemplate;
    private final RateLimitProperties properties;

    @Override
    public RateLimitDecision tryAcquire(final RateLimitRequest request) {
        validateProperties();

        String bucketKey = buildBucketKey(request);
        String decisionKey = buildDecisionKey(request);

        List<?> result = redisTemplate.execute(
                SCRIPT,
                List.of(bucketKey, decisionKey),
                String.valueOf(properties.getCapacity()),
                String.valueOf(properties.getRefillTokensPerSecond()),
                String.valueOf(request.requestedTokens()),
                String.valueOf(properties.getBucketTtl().toMillis()),
                String.valueOf(properties.getDecisionTtl().toMillis())
        );

        if (result.size() < 3) {
            throw new IllegalStateException("redis限流脚本返回结果异常");
        }

        long allowed = number(result.get(0));
        long retryAfter = number(result.get(1));
        long remaining = number(result.get(2));

        if (allowed == 1) {
            return RateLimitDecision.allowed(remaining);
        }

        return RateLimitDecision.denied(retryAfter, remaining);
    }

    /**
     * 构建限流决策缓存key，覆盖租户、应用、渠道
     * 多租户、应用、渠道维度限流
     *
     * @param request
     * @return
     */
    private String buildBucketKey(RateLimitRequest request) {
        return String.format(
                "notify:rate:{%d}:app:%d:channel:%s",
                request.tenantId(),
                request.applicationId(),
                request.channelType()
        );
    }

    /**
     * 构建限流决策缓存key，覆盖租户、事件ID
     * 用于缓存限流结果
     *
     * @param request
     * @return
     */
    private String buildDecisionKey(RateLimitRequest request) {
        return String.format(
                "notify:rate:{%d}:decision:%s",
                request.tenantId(),
                request.eventId()
        );
    }

    private long number(Object value) {
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("限流脚本返回结果异常：" + value);
        }
        return number.longValue();
    }

    private void validateProperties() {
        if (properties.getCapacity() <= 0) {
            throw new IllegalStateException("限流容量不能小于等于0");
        }
        if (properties.getRefillTokensPerSecond() <= 0) {
            throw new IllegalStateException("限流填充速率不能小于等于0");
        }
    }
}
