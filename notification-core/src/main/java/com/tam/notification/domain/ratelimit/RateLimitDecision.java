package com.tam.notification.domain.ratelimit;

/**
 * 限流结果
 *
 * @param allowed
 * @param retryAfterMillis
 * @param remainingTokens
 */
public record RateLimitDecision(
        boolean allowed,
        long retryAfterMillis,
        long remainingTokens
) {
    public static RateLimitDecision allowed(
            long remainingTokens
    ) {
        return new RateLimitDecision(
                true,
                0,
                remainingTokens
        );
    }

    public static RateLimitDecision denied(
            long retryAfterMillis,
            long remainingTokens
    ) {
        return new RateLimitDecision(
                false,
                retryAfterMillis,
                remainingTokens
        );
    }
}
