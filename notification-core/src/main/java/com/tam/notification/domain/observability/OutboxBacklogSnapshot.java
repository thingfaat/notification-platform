package com.tam.notification.domain.observability;


/**
 * outbox运维快照
 * 这里只携带跨租户聚合结果，不携带惹怒我租户或消息明细
 */
public record OutboxBacklogSnapshot(
        long pendingCount, // 待处理消息数
        long deadCount, // 死信数
        long oldestPendingAgeSeconds // 最后一条待处理消息的延迟
) {
    public OutboxBacklogSnapshot {
        if (pendingCount < 0
                || deadCount < 0
                || oldestPendingAgeSeconds < 0) {
            throw new IllegalArgumentException("Outbox 指标不能为负数");
        }
    }
}
