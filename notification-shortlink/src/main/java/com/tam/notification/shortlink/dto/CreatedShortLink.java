package com.tam.notification.shortlink.dto;

import com.tam.notification.domain.enums.ShortLinkStatus;

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
