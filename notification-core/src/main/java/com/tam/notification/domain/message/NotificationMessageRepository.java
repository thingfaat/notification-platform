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
     * RetryScheduler系统级跨租户扫描
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
     * 人工重试使用Cas执行 dead->queue
     *
     * @param id
     * @return
     */
    boolean requeueDead(Long id);
}
