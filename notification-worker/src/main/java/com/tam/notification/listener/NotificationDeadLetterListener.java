package com.tam.notification.listener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tam.notification.common.tenant.TenantContext;
import com.tam.notification.common.trace.TraceContext;
import com.tam.notification.domain.outbox.NotificationSendEvent;
import com.tam.notification.service.MessageSendTransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "%DLQ%${notification.mq.consumer-group}",
        consumerGroup = "${notification.mq.dlq-consumer-group}",
        messageModel = MessageModel.CLUSTERING,
        consumeMode = ConsumeMode.CONCURRENTLY,
        maxReconsumeTimes = 3
)
public class NotificationDeadLetterListener implements RocketMQListener<String> {

    private final ObjectMapper objectMapper;
    private final MessageSendTransactionService transactionService;

    @Value("${notification.mq.dlq-consumer-group}")
    private String dlqConsumerGroup;


    @Override
    public void onMessage(final String message) {
        log.info("接收到消息: {}", message);
        NotificationSendEvent event = deserialize(message);
        try {
            TenantContext.setTenantId(event.tenantId());
            if (event.traceId() != null) {
                TraceContext.setTraceId(event.traceId());
            }
            transactionService.finishDeadLetter(event, dlqConsumerGroup);
            log.error("消息已进入Rocket mq dlq，eventId= {}, messageId = {}", event.eventId(), event.messageId());
        } finally {
            TraceContext.clear();
            TenantContext.clear();
        }
    }

    private NotificationSendEvent deserialize(String payload) {
        try {
            return objectMapper.readValue(
                    payload,
                    NotificationSendEvent.class
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Invalid DLQ message",
                    exception
            );
        }
    }
}
