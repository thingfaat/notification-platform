package com.tam.notification.domain.ratelimit;

/**
 * 限流领域接口
 */
public interface RateLimiter {
    RateLimitDecision tryAcquire(RateLimitRequest request);
}
