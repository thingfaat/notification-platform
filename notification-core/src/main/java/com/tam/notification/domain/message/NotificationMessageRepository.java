package com.tam.notification.domain.message;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface NotificationMessageRepository {
    NotificationMessage save(NotificationMessage message);

    Optional<NotificationMessage> findById(Long id);

    List<NotificationMessage> findByTaskId(Long taskId);

    void update(NotificationMessage message);

    /**
     * 查询到期需要重新入队的消息：
     * RETRY_WAIT或THROTTLED
     *
     * @param limit
     * @param now
     * @return
     */
    List<NotificationMessage> findRetryableAcrossTenants(int limit, LocalDateTime now);

    /**
     * CAS执行 RETRY_WAIT → QUEUED。
     */
    boolean requeueIfDue(Long id, LocalDateTime now);

    /**
     * CAS执行 RETRY_WAIT/THROTTLED → QUEUED
     *
     * @param id
     * @return
     */
    boolean requeueDead(Long id);
}
