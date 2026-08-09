package com.tam.notification.domain.ratelimit;

import com.tam.notification.domain.enums.ChannelType;

import java.util.Objects;

/**
 * 限流请求
 *
 * @param eventId
 * @param tenantId
 * @param applicationId
 * @param channelType
 * @param requestedTokens
 */
public record RateLimitRequest(
        String eventId,
        Long tenantId,
        Long applicationId,
        ChannelType channelType,
        int requestedTokens
) {
    public RateLimitRequest {
        Objects.requireNonNull(eventId, "eventId不能为空");
        Objects.requireNonNull(tenantId, "tenantId不能为空");
        Objects.requireNonNull(applicationId, "applicationId不能为空");
        Objects.requireNonNull(channelType, "channelType不能为空");

        if (requestedTokens <= 0) {
            throw new IllegalArgumentException("requestTokens必须大于0");
        }
    }

    public static RateLimitRequest oneToken(String eventId, Long tenantId, Long applicationId, ChannelType channelType) {
        return new RateLimitRequest(
                eventId,
                tenantId,
                applicationId,
                channelType,
                1
        );
    }
}
