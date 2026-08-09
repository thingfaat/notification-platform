package com.tam.notification.shortlink.service;

import com.tam.notification.shortlink.domain.ShortLinkStatus;

import java.time.LocalDateTime;

public record CreatedShortLink(
        Long id,
        Long tenantId,
        Long applicationId,
        String shortCode,
        String originalUrl,
        LocalDateTime expiredAt,
        ShortLinkStatus status
) {
}
