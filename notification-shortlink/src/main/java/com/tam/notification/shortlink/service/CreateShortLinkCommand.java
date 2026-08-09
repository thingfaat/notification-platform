package com.tam.notification.shortlink.service;

import java.time.LocalDateTime;

public record CreateShortLinkCommand(
        Long applicationId,
        String originalUrl,
        LocalDateTime expireAt
) {
}
