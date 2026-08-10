package com.tam.notification.vo;

import com.tam.notification.shortlink.dto.CreatedShortLink;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ShortLinkResponse {
    private Long id;

    private Long tenantId;

    private Long applicationId;

    private String shortCode;

    private String originalUrl;

    private LocalDateTime expireAt;

    private String status;

    public static ShortLinkResponse from(
            CreatedShortLink createdShortLink
    ) {
        ShortLinkResponse response = new ShortLinkResponse();
        response.setId(createdShortLink.id());
        response.setTenantId(createdShortLink.tenantId());
        response.setApplicationId(createdShortLink.applicationId());
        response.setShortCode(createdShortLink.shortCode());
        response.setOriginalUrl(createdShortLink.originalUrl());
        response.setExpireAt(createdShortLink.expiredAt());
        response.setStatus(createdShortLink.status().name());

        return response;
    }
}
