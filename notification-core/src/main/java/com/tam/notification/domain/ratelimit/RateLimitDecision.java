package com.tam.notification.domain.ratelimit;

import java.util.Objects;

/**
 * 限流结果
 *
 * @param allowed             是否允许发送
 * @param retryAfterMillis    被拒绝后建议等待时间
 * @param remainingTokens     剩余令牌数
 * @param remainingDailyQuota 今日剩余配额
 * @param reason              拒绝原因
 */
public record RateLimitDecision(
        boolean allowed,
        long retryAfterMillis,
        long remainingTokens,
        long remainingDailyQuota,
        RateLimitReason reason
) {

    public RateLimitDecision {
        Objects.requireNonNull(reason, "reason不能为空");

        if (retryAfterMillis < 0) {
            throw new IllegalArgumentException(
                    "retryAfterMillis不能小于0"
            );
        }

        if (allowed && reason != RateLimitReason.NONE) {
            throw new IllegalArgumentException(
                    "允许发送时reason必须为NONE"
            );
        }

        if (!allowed && reason == RateLimitReason.NONE) {
            throw new IllegalArgumentException(
                    "拒绝发送时必须提供拒绝原因"
            );
        }
    }

    public static RateLimitDecision allowed(
            long remainingTokens,
            long remainingDailyQuota
    ) {
        return new RateLimitDecision(
                true,
                0,
                remainingTokens,
                remainingDailyQuota,
                RateLimitReason.NONE
        );
    }

    public static RateLimitDecision denied(
            long retryAfterMillis,
            long remainingTokens,
            long remainingDailyQuota,
            RateLimitReason reason
    ) {
        return new RateLimitDecision(
                false,
                retryAfterMillis,
                remainingTokens,
                remainingDailyQuota,
                reason
        );
    }
}
