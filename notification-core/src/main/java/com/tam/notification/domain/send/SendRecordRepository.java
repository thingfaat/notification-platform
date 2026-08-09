package com.tam.notification.domain.send;

import java.time.LocalDateTime;
import java.util.Optional;

public interface SendRecordRepository {

    SendRecord save(SendRecord sendRecord);

    Optional<SendRecord> findByMessageIdAndAttemptNo(Long messageId, Integer attemptNo);

    boolean markSuccess(
            Long id,
            String providerMessageId,
            LocalDateTime finishedAt
    );

    boolean markFailed(
            Long id,
            String failureCode,
            String failureReason,
            LocalDateTime finishedAt
    );
}
