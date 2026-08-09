package com.tam.notification.domain.outbox;

public interface ConsumeRecordRepository {

    boolean exists(Long tenantId, String consumerGroup, String eventId);

    boolean tryCreate(Long tenantId, String consumerGroup, String eventId, Long messageId);
}
