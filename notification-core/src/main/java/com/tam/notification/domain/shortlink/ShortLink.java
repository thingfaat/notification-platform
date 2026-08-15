package com.tam.notification.domain.shortlink;

import com.tam.notification.domain.enums.ShortLinkStatus;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ShortLink {
    private Long id;

    private Long tenantId;

    private Long applicationId;

    /** 区分管理短链和消息追踪短链。 */
    private ShortLinkBusinessType businessType;

    /**
     * 业务类型内部的幂等键。
     * 数据库会联合 tenantId、applicationId、businessType 建立唯一约束。
     */
    private String idempotencyKey;

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
