package com.tam.notification.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tam.notification.domain.shortlink.ShortLinkMapping;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("short_link_mapping")
public class ShortLinkMappingDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;

    private Long shortLinkId;

    private String shortCode;

    private LocalDateTime createdAt;

    public ShortLinkMapping toDomain(ShortLinkMappingDO data) {
        ShortLinkMapping mapping = new ShortLinkMapping();

        mapping.setId(data.getId());
        mapping.setTenantId(data.getTenantId());
        mapping.setShortLinkId(data.getShortLinkId());
        mapping.setShortCode(data.getShortCode());
        mapping.setCreatedAt(data.getCreatedAt());

        return mapping;
    }
}
