package com.tam.notification.ratelimit;

import com.tam.notification.domain.ratelimit.RateLimitDecision;
import com.tam.notification.domain.ratelimit.RateLimitReason;
import com.tam.notification.domain.ratelimit.RateLimitRequest;
import com.tam.notification.domain.ratelimit.RateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisTokenBucketRateLimiter implements RateLimiter {

    /**
     * 返回值：
     * <p>
     * 1. allowed
     * 2. retryAfterMillis
     * 3. remainingTokens
     * 4. remainingDailyQuota
     * 5. reasonCode
     */
    private static final String LUA_SCRIPT = """
            local bucketKey = KEYS[1]
            local quotaKey = KEYS[2]
            local decisionKey = KEYS[3]
            
            local capacity = tonumber(ARGV[1])
            local refillRate = tonumber(ARGV[2])
            local requested = tonumber(ARGV[3])
            local bucketTtl = tonumber(ARGV[4])
            local decisionTtl = tonumber(ARGV[5])
            local dailyQuota = tonumber(ARGV[6])
            local quotaTtl = tonumber(ARGV[7])
            
            -- 拒绝原因：
            -- 0 = NONE
            -- 1 = TOKEN_BUCKET_EXHAUSTED
            -- 2 = DAILY_QUOTA_EXHAUSTED
            
            -- 同一个eventId重复执行时，复用第一次判定。
            local cachedAllowed =
                redis.call('HGET', decisionKey, 'allowed')
            
            if cachedAllowed then
                local cachedRetry =
                    redis.call('HGET', decisionKey, 'retry_after')
                local cachedRemainingTokens =
                    redis.call('HGET', decisionKey, 'remaining_tokens')
                local cachedRemainingQuota =
                    redis.call('HGET', decisionKey, 'remaining_quota')
                local cachedReason =
                    redis.call('HGET', decisionKey, 'reason')
            
                return {
                    tonumber(cachedAllowed),
                    tonumber(cachedRetry or '0'),
                    tonumber(cachedRemainingTokens or '0'),
                    tonumber(cachedRemainingQuota or '0'),
                    tonumber(cachedReason or '0')
                }
            end
            
            -- 使用Redis服务端时间，避免多个Worker机器时间不一致。
            local redisTime = redis.call('TIME')
            local now =
                tonumber(redisTime[1]) * 1000
                + math.floor(tonumber(redisTime[2]) / 1000)
            
            -- 读取令牌桶。
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
            
            -- 读取今日配额。
            local quotaExists = redis.call('EXISTS', quotaKey)
            local dailyUsed = tonumber(
                redis.call('GET', quotaKey) or '0'
            )
            
            local quotaAvailable =
                dailyUsed + requested <= dailyQuota
            
            local allowed = 0
            local retryAfter = 0
            local reason = 0
            
            -- 日配额优先。
            -- 即使令牌也不足，也必须等待到配额窗口重置。
            if not quotaAvailable then
                reason = 2
            
                local currentQuotaTtl =
                    redis.call('PTTL', quotaKey)
            
                if currentQuotaTtl > 0 then
                    retryAfter = currentQuotaTtl
                else
                    retryAfter = quotaTtl
                end
            
            elseif tokens < requested then
                reason = 1
            
                retryAfter = math.ceil(
                    (requested - tokens)
                    * 1000
                    / refillRate
                )
            
            else
                -- 只有两个条件都通过时才同时修改两个资源。
                tokens = tokens - requested
                dailyUsed = dailyUsed + requested
                allowed = 1
            
                redis.call(
                    'INCRBY',
                    quotaKey,
                    requested
                )
            
                -- 只在创建当日配额Key时设置到下一个自然日的TTL。
                -- 后续请求不能刷新TTL，否则会变成滑动窗口。
                if quotaExists == 0 then
                    redis.call(
                        'PEXPIRE',
                        quotaKey,
                        quotaTtl
                    )
                end
            end
            
            local remainingTokens = math.floor(tokens)
            local remainingDailyQuota = math.max(
                0,
                dailyQuota - dailyUsed
            )
            
            -- 即使本次被配额拒绝，也保存补充后的令牌状态。
            -- 但不会真正扣减令牌。
            redis.call(
                'HSET',
                bucketKey,
                'tokens',
                tostring(tokens),
                'last_refill',
                tostring(now)
            )
            
            redis.call(
                'PEXPIRE',
                bucketKey,
                bucketTtl
            )
            
            -- 缓存eventId第一次判定结果。
            redis.call(
                'HSET',
                decisionKey,
                'allowed',
                tostring(allowed),
                'retry_after',
                tostring(retryAfter),
                'remaining_tokens',
                tostring(remainingTokens),
                'remaining_quota',
                tostring(remainingDailyQuota),
                'reason',
                tostring(reason)
            )
            
            redis.call(
                'PEXPIRE',
                decisionKey,
                decisionTtl
            )
            
            return {
                allowed,
                retryAfter,
                remainingTokens,
                remainingDailyQuota,
                reason
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
        Objects.requireNonNull(request, "request不能为空");
        validateProperties();

        String bucketKey = buildBucketKey(request);
        String quotaKey = buildQuotaKey(request);
        String decisionKey = buildDecisionKey(request);

        long quotaTtlMillis = millisUntilNextQuotaWindow();

        List<?> result = redisTemplate.execute(
                SCRIPT,
                List.of(bucketKey, quotaKey, decisionKey),
                String.valueOf(properties.getCapacity()),
                String.valueOf(properties.getRefillTokensPerSecond()),
                String.valueOf(request.requestedTokens()),
                String.valueOf(properties.getBucketTtl().toMillis()),
                String.valueOf(properties.getDecisionTtl().toMillis()),
                String.valueOf(properties.getDailyQuota()),
                String.valueOf(quotaTtlMillis)
        );

        if (result == null || result.size() < 5) {
            throw new IllegalStateException("redis限流脚本返回结果异常");
        }

        long allowed = number(result.get(0));
        long retryAfterMillis = number(result.get(1));
        long remainingTokens = number(result.get(2));
        long remainingDailyQuota = number(result.get(3));
        long reasonCode = number(result.get(4));

        if (allowed == 1) {
            return RateLimitDecision.allowed(remainingTokens, remainingDailyQuota);
        }

        return RateLimitDecision.denied(
                retryAfterMillis,
                remainingTokens,
                remainingDailyQuota,
                RateLimitReason.fromCode(reasonCode)
        );
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
     * 计算当前时间到下一个自然日零点的毫秒数。
     * <p>
     * 使用ZonedDateTime而不是固定24小时，
     * 可以正确处理夏令时地区一天不是24小时的情况。
     */
    private long millisUntilNextQuotaWindow() {
        ZonedDateTime now = ZonedDateTime.now(properties.getQuotaZoneId());

        ZonedDateTime nextReset = now
                .toLocalDate()
                .plusDays(1)
                .atStartOfDay(properties.getQuotaZoneId());

        return Math.max(
                1,
                Duration.between(now, nextReset).toMillis()
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

    private String buildQuotaKey(RateLimitRequest request) {
        return String.format(
                "notify:quota:{%d}:app:%d:channel:%s:daily",
                request.tenantId(),
                request.applicationId(),
                request.channelType()
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
