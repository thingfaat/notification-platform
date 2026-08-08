package com.tam.notification.domain.outbox;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OutboxEvent {
    private Long id;
    private Long tenantId;
    private String eventId;
    private String aggregateType;
    private Long aggregateId;
    private String eventType;
    private String topic;
    private String payload;
    private OutboxStatus publishStatus;
    private Integer retryCount;
    private LocalDateTime nextRetryTime;
    private String lastError;
    private LocalDateTime publishedAt;
}
