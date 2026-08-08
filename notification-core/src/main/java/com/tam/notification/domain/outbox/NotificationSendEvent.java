package com.tam.notification.domain.outbox;

import java.time.LocalDateTime;

public record NotificationSendEvent (
        String eventId,
        Long tenantId,
        Long applicationId,
        Long taskId,
        Long messageId,
        String messageNo,
        String eventType,
        String traceId,
        LocalDateTime occurredAt
) {
}
