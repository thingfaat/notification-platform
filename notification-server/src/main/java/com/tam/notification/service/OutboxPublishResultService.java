package com.tam.notification.service;

import com.tam.notification.common.exception.BusinessException;
import com.tam.notification.common.exception.CommonErrorCode;
import com.tam.notification.domain.enums.MessageStatus;
import com.tam.notification.domain.message.NotificationMessage;
import com.tam.notification.domain.message.NotificationMessageRepository;
import com.tam.notification.domain.outbox.OutboxEvent;
import com.tam.notification.domain.outbox.OutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class OutboxPublishResultService {
    private final OutboxRepository outboxRepository;
    private final NotificationMessageRepository messageRepository;

    @Transactional
    public void markPublished(OutboxEvent event) {
        outboxRepository.markPublished(event.getId());
        NotificationMessage message = messageRepository.findById(event.getAggregateId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.BUSINESS_ERROR, "消息不存在"));
        if (message.getMessageStatus() == MessageStatus.CREATED) {
            message.changeStatus(MessageStatus.QUEUED);
            messageRepository.update(message);
        }
    }

    @Transactional
    public void markFailed(OutboxEvent event, Exception e) {
        int retryCount = event.getRetryCount() + 1;
        LocalDateTime nextRetryTime = LocalDateTime.now().plusSeconds(calculateDelay(retryCount));
        outboxRepository.markFailed(event.getId(), retryCount, nextRetryTime, e.getMessage());
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
