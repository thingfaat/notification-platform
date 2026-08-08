package com.tam.notification.service;

import com.tam.notification.domain.message.NotificationMessageRepository;
import com.tam.notification.domain.outbox.OutboxEvent;
import com.tam.notification.domain.outbox.OutboxRepository;
import com.tam.notification.domain.outbox.OutboxStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxPublishResultService {
    private final OutboxRepository outboxRepository;

    @Value("${notification.outbox.max-retry-count:3}")
    private Integer maxRetryCount;

    @Transactional
    public void markPublished(OutboxEvent event, String publisherId) {
        final var success = outboxRepository.markPublished(event.getId(), publisherId);
        if (!success) {
            throw new IllegalStateException("Outbox发布状态更新失败，Claim可能已失效：" + event.getEventId());
        }
    }

    @Transactional
    public void markFailed(OutboxEvent event, String publisherId, Exception e) {
        int retryCount = event.getRetryCount() + 1;
        OutboxStatus targetStatus;
        LocalDateTime nextRetryTime;
        if (retryCount >= maxRetryCount) {
            targetStatus = OutboxStatus.DEAD;
            nextRetryTime = null;
        } else {
            targetStatus = OutboxStatus.FAILED;
            nextRetryTime = LocalDateTime.now().plusSeconds(calculateDelay(retryCount));
        }

        final var success = outboxRepository.markFailed(event.getId(), publisherId, targetStatus, retryCount, nextRetryTime, truncateError(e.getMessage()));
        if (!success) {
            log.warn("Outbox发布状态更新失败，Claim可能已被其他实例接管，eventId = {}", event.getEventId());
        }
    }

    /**
     * 截断错误信息
     *
     * @param error
     * @return
     */
    private String truncateError(final String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 1000 ? error : error.substring(0, 1000);
    }

    /**
     * 计算延迟时间
     *
     * @param retryCount
     * @return
     */
    private long calculateDelay(final int retryCount) {
        return Math.min(60, 5L << Math.min(retryCount - 1, 4)); // 最大延迟60秒，每次递增5秒
    }
}
