package com.tam.notification.domain.observability;

/**
 * Outbox 运维快照。
 *
 * <p>这里只携带跨租户聚合结果，不携带任何租户或消息明细。</p>
 */
public record OutboxBacklogSnapshot(
        long pendingCount, // 待处理消息数
        long deadCount, // 死信数
        long oldestPendingAgeSeconds // 最老一条待处理消息的等待时间
) {
    public OutboxBacklogSnapshot {
        if (pendingCount < 0
                || deadCount < 0
                || oldestPendingAgeSeconds < 0) {
            throw new IllegalArgumentException("Outbox 指标不能为负数");
        }
    }
}
