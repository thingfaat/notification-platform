package com.tam.notification.shortlink.dto;

public record ResolvedShortLink(
        String shortCode,
        Long tenantId,
        Long shortLinkId,
        String originalUrl
) {
}
