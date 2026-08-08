package com.tam.notification.domain.message;

import java.util.List;
import java.util.Optional;

public interface NotificationMessageRepository {
    NotificationMessage save(NotificationMessage message);

    Optional<NotificationMessage> findById(Long id);

    List<NotificationMessage> findByTaskId(Long taskId);

    void update(NotificationMessage message);
}
