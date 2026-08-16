package com.tam.notification.listener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tam.notification.common.tenant.TenantContext;
import com.tam.notification.common.trace.TraceContext;
import com.tam.notification.domain.outbox.NotificationSendEvent;
import com.tam.notification.service.NotificationSendOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQPushConsumerLifecycleListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "${notification.mq.topic}",
        consumerGroup = "${notification.mq.consumer-group}",
        messageModel = MessageModel.CLUSTERING,
        consumeMode = ConsumeMode.CONCURRENTLY,
        maxReconsumeTimes = 3
)
public class NotificationSendListener implements RocketMQListener<MessageExt>, RocketMQPushConsumerLifecycleListener {
    private final ObjectMapper objectMapper;
    private final NotificationSendOrchestrator sendOrchestrator;
    private final WorkerIdentity workerIdentity;

    @Override
    public void prepareStart(final DefaultMQPushConsumer consumer) {
        // 显式设置实例名，Dashboard和日志都能区分worker-a、worker-b
        workerIdentity.configure(consumer, "notification-send");
    }

    @Override
    public void onMessage(final MessageExt message) {
        final var payload = new String(message.getBody(), StandardCharsets.UTF_8);

        NotificationSendEvent event = deserialize(payload);
        log.info(
                "收到通知消息，worker={}, eventId={}, messageId={}, mqMsgId={}, queueId={}, queueOffset={}, reconsumeTimes={}",
                workerIdentity.instanceId(),
                event.eventId(),
                event.messageId(),
                message.getMsgId(),
                message.getQueueId(),
                message.getQueueOffset(),
                message.getReconsumeTimes()
        );

        try {
            TenantContext.setTenantId(event.tenantId());
            if (event.traceId() != null) {
                TraceContext.setTraceId(event.traceId());
            }
            // 不吞异常：异常必须继续抛给RocketMQ，才能触发重投和DLQ
            sendOrchestrator.send(event);
        } finally {
            // MQ 消费线程会复用，不清理会造成跨租户、跨 Trace 串线
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
