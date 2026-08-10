package com.tam.notification.domain.shortlink;

import com.tam.notification.domain.enums.ShortLinkStatus;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ShortLink {
    private Long id;

    private Long tenantId;

    private Long applicationId;

    private String originalUrl;

    private LocalDateTime expireAt;

    private ShortLinkStatus status;

    private Integer version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public boolean isExpired(LocalDateTime now) {
        return expireAt != null && !expireAt.isAfter(now);
    }

    public boolean isAvailable(LocalDateTime now) {
        return status == ShortLinkStatus.ACTIVE && !isExpired(now);
    }
}
