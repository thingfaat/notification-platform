package com.tam.notification.domain.shortlink;

import java.time.LocalDateTime;

public record ShortLinkCacheEntry(
        Long tenantId,
        Long shortLinkId,
        String originalUrl,
        LocalDateTime expireAt
) {
    public boolean isExpired(LocalDateTime now) {
        return expireAt != null && !expireAt.isAfter(now);
    }
}
