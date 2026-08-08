package com.tam.notification.listener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tam.notification.common.tenant.TenantContext;
import com.tam.notification.common.trace.TraceContext;
import com.tam.notification.domain.outbox.NotificationSendEvent;
import com.tam.notification.service.NotificationConsumeService;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = "${notification.mq.topic}", consumerGroup = "${notification.mq.consumer-group}", messageModel = MessageModel.CLUSTERING, consumeMode = ConsumeMode.CONCURRENTLY, maxReconsumeTimes = 3)
public class NotificationSendListener implements RocketMQListener<String> {
    private final ObjectMapper objectMapper;
    private final NotificationConsumeService consumeService;

    @Override
    public void onMessage(final String payload) {
        NotificationSendEvent event = deserialize(payload);
        try {
            TenantContext.setTenantId(event.tenantId());
            if (event.traceId() != null) {
                TraceContext.setTraceId(event.traceId());
            }
            consumeService.consume(event);
        } finally {
            // mq消费者线程会复用，两个都必须清空
            TenantContext.clear();
            TraceContext.clear();
        }
    }

    private NotificationSendEvent deserialize(final String payload) {
        try {
            return objectMapper.readValue(payload, NotificationSendEvent.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid MQ message", e);
        }
    }
}
