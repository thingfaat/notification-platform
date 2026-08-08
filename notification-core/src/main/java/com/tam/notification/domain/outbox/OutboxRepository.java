package com.tam.notification.domain.outbox;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxRepository {
    OutboxEvent save(OutboxEvent event);

    List<OutboxEvent> findPending(int limit);

    void markPublished(Long id);

    void markFailed(Long id, Integer retryCount, LocalDateTime nextRetryTime, String error);
}
