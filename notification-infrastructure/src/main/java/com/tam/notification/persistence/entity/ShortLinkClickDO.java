package com.tam.notification.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("short_link_click")
public class ShortLinkClickDO {
    @TableId(type = IdType.INPUT)
    private Long id;

    private Long tenantId;

    private String eventId;

    private Long shortLinkId;

    private String shortCode;

    private String visitorKey;

    private LocalDateTime clickedAt;

    private LocalDateTime createdAt;
}
