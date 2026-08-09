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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 人工重试服务
 */
@Service
@RequiredArgsConstructor
public class NotificationManualRetryService {

    private final NotificationMessageRepository messageRepository;
    private final NotificationTaskRepository taskRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Value("${notification.mq.topic}")
    private String topic;

    @Transactional
    public void retry(Long messageId) {
        // cas保证并发点击人工重试时只有一个请求成功
        boolean requeued = messageRepository.requeueDead(messageId);
        if (!requeued) {
            throw new BusinessException(CommonErrorCode.BUSINESS_ERROR, "消息不存在、租户不匹配或消息当前不是DEAD状态");
        }

        NotificationMessage message = messageRepository.findById(messageId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.BUSINESS_ERROR, "消息不存在"));
        NotificationTask task = taskRepository.findById(message.getTaskId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.BUSINESS_ERROR, "任务不存在"));

        String eventId = UUID.randomUUID()
                .toString()
                .replace("-", "");

        NotificationSendEvent sendEvent = new NotificationSendEvent(
                eventId,
                message.getTenantId(),
                task.getApplicationId(),
                task.getId(),
                message.getId(),
                message.getMessageNo(),
                "NOTIFICATION_SEND",
                TraceContext.getTraceId(),
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
    }

    private String serialize(NotificationSendEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception exception) {
            throw new BusinessException(
                    CommonErrorCode.BUSINESS_ERROR,
                    "人工重试事件序列化失败"
            );
        }
    }
}
