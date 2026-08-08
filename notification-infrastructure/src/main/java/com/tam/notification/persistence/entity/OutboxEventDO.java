package com.tam.notification.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.tam.notification.domain.outbox.OutboxStatus;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("notify_outbox")
public class OutboxEventDO {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private String eventId;

    private String aggregateType;

    private Long aggregateId;

    private String eventType;

    private String topic;

    private String payload;

    private String publishStatus;

    private Integer retryCount;

    private LocalDateTime nextRetryTime;

    private String lastError;

    private LocalDateTime publishedAt;
}
