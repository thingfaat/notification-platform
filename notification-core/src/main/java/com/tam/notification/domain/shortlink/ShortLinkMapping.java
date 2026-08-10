package com.tam.notification.domain.shortlink;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ShortLinkMapping {
    private Long id;

    private Long tenantId;

    private Long shortLinkId;

    private String shortCode;

    private LocalDateTime createdAt;
}
