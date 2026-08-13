package com.tam.notification.domain.ratelimit;

/**
 * 限流拒绝原因
 */
public enum RateLimitReason {

    NONE(0),
    TOKEN_BUCKET_EXHAUSTED(1),
    DAILY_QUOTA_EXHAUSTED(2);

    private final long code;

    RateLimitReason(long code) {
        this.code = code;
    }

    public long code() {
        return code;
    }

    public static RateLimitReason fromCode(long code) {
        for (final var reason : values()) {
            if (reason.code() == code) {
                return reason;
            }
        }

        throw new IllegalArgumentException("未知限流原因编码：" + code);
    }
}
