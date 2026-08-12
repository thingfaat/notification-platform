package com.tam.notification.domain.shortlink;

import java.time.LocalDateTime;

/**
 * 点击事件
 *
 * @param eventId
 * @param tenantId
 * @param shortLinkId
 * @param shortCode
 * @param visitorKey
 * @param traceId
 * @param occurredAt
 */
public record ShortLinkClickEvent(
        String eventId,
        Long tenantId,
        Long shortLinkId,
        String shortCode,
        String visitorKey,
        String traceId,
        LocalDateTime occurredAt
) {
}
