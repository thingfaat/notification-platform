package com.tam.notification.domain.outbox;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxRepository {
    OutboxEvent save(OutboxEvent event);

    /**
     * 跨租户查询可以抢占的outbox
     *
     * @param limit
     * @param claimExpireBefore
     * @return
     */
    List<OutboxEvent> findClaimable(int limit, LocalDateTime claimExpireBefore);

    boolean tryClaim(Long id, String lockOwner, LocalDateTime claimExpiredBefore);

    /**
     * 只有持有锁的实例才能完成消息
     *
     * @param id
     * @param lockOwner
     */
    boolean markPublished(Long id, String lockOwner);

    /**
     * 只有持有锁的实力才能标记失败
     *
     * @param id
     * @param lockOwner
     * @param targetStatus
     * @param retryCount
     * @param nextRetryTime
     * @param error
     */
    boolean markFailed(Long id, String lockOwner, OutboxStatus targetStatus, Integer retryCount, LocalDateTime nextRetryTime, String error);
}
