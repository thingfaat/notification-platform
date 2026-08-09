package com.tam.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tam.notification.common.exception.BusinessException;
import com.tam.notification.common.exception.CommonErrorCode;
import com.tam.notification.common.trace.TraceContext;
import com.tam.notification.domain.message.NotificationMessage;
import com.tam.notification.domain.message.NotificationMessageRepository;
import com.tam.notification.domain.outbox.NotificationSendEvent;
import com.tam.notification.domain.outbox.OutboxEvent;
import com.tam.notification.domain.outbox.OutboxRepository;
import com.tam.notification.domain.outbox.OutboxStatus;
import com.tam.notification.domain.task.NotificationTask;
import com.tam.notification.domain.task.NotificationTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RetryScheduleService {
    private final NotificationMessageRepository messageRepository;
    private final NotificationTaskRepository taskRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Value("${notification.mq.topic}")
    private String topic;

    @Transactional
    public boolean requeue(Long messageId) {
        LocalDateTime now = LocalDateTime.now();

        // 两个worker可能同时扫描到，cas保证只有其中一个 RETRY_WAIT -> QUQUED
        boolean success = messageRepository.requeueIfDue(messageId, now);
        if (!success) {
            return false;
        }

        NotificationMessage message = messageRepository.findById(messageId)
                .orElseThrow(() -> new IllegalArgumentException("Message not found"));
        NotificationTask task = taskRepository.findById(message.getTaskId())
                .orElseThrow(() -> new IllegalArgumentException("Task not found"));

        String eventId = UUID.randomUUID().toString().replace("-", "");
        String traceId = TraceContext.getTraceId();

        NotificationSendEvent sendEvent = new NotificationSendEvent(
                eventId,
                message.getTenantId(),
                task.getApplicationId(),
                task.getId(),
                message.getId(),
                message.getMessageNo(),
                "NOTIFICATION_SEND",
                traceId,
                LocalDateTime.now()
        );

        OutboxEvent outbox = new OutboxEvent();
        outbox.setTenantId(message.getTenantId());
        outbox.setEventId(eventId);
        outbox.setAggregateType("NOTIFICATION_MESSAGE");
        outbox.setAggregateId(message.getId());
        outbox.setEventType("NOTIFICATION_SEND");
        outbox.setTopic(topic);
        outbox.setPayload(serialize(sendEvent));
        outbox.setPublishStatus(OutboxStatus.NEW);
        outbox.setRetryCount(0);
        outboxRepository.save(outbox);
        return true;
    }

    private String serialize(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new BusinessException(CommonErrorCode.BUSINESS_ERROR, "重试事件序列化失败");
        }
    }
}
