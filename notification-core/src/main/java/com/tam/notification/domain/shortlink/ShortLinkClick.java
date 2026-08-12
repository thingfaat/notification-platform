package com.tam.notification.domain.shortlink;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 点击领域对象
 */
@Data
public class ShortLinkClick {

    private Long id;

    private Long tenantId;

    private String eventId;

    private Long shortLinkId;

    private String shortCode;

    private String visitorKey;

    private LocalDateTime clickedAt;

    private LocalDateTime createdAt;
}
