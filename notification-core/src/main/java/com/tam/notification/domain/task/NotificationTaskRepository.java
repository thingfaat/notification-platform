package com.tam.notification.domain.task;

import java.util.Optional;

public interface NotificationTaskRepository {
    NotificationTask save(NotificationTask task);

    Optional<NotificationTask> findById(Long id);

    void update(NotificationTask task);

    Optional<NotificationTask> findByRequestId(Long applicationId, String requestId);
}
