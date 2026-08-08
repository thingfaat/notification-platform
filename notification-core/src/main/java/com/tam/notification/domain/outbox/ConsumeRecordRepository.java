package com.tam.notification.domain.outbox;

public interface ConsumeRecordRepository {
    boolean tryCreate(Long tenantId, String consumerGroup, String eventId, Long messageId);
}
