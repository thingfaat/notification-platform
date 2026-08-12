package com.tam.notification.listener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tam.notification.common.tenant.TenantContext;
import com.tam.notification.common.trace.TraceContext;
import com.tam.notification.domain.shortlink.ShortLinkClickEvent;
import com.tam.notification.service.ShortLinkClickTransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "${notification.shortlink.click.topic}",
        consumerGroup = "${notification.shortlink.click.consumer-group}",
        messageModel = MessageModel.CLUSTERING,
        consumeMode = ConsumeMode.CONCURRENTLY,
        maxReconsumeTimes = 3
)
public class ShortLinkClickListener implements RocketMQListener<String> {
    private final ObjectMapper objectMapper;
    private final ShortLinkClickTransactionService transactionService;

    @Override
    public void onMessage(final String payload) {
        ShortLinkClickEvent event = deserialize(payload);

        try {
            TenantContext.setTenantId(event.tenantId());

            if (event.traceId() != null) {
                TraceContext.setTraceId(event.traceId());
            }

            transactionService.record(event);

            log.debug(
                    "short-link click consumed, eventId={}, shortCode={}",
                    event.eventId(),
                    event.shortCode()
            );
        } finally {
            // RocketMQ 消费线程会复用。
            TenantContext.clear();
            TraceContext.clear();
        }
    }

    private ShortLinkClickEvent deserialize(String payload) {
        try {
            return objectMapper.readValue(
                    payload,
                    ShortLinkClickEvent.class
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "invalid short-link click event",
                    exception
            );
        }
    }
}
