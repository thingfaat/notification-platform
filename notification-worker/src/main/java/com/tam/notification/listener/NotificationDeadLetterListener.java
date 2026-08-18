package com.tam.notification.listener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tam.notification.common.tenant.TenantContext;
import com.tam.notification.common.trace.TraceContext;
import com.tam.notification.domain.outbox.NotificationSendEvent;
import com.tam.notification.service.MessageSendTransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQPushConsumerLifecycleListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.tam.notification.observability.MqConsumeMetrics;

import java.nio.charset.StandardCharsets;

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
public class NotificationDeadLetterListener implements RocketMQListener<MessageExt>, RocketMQPushConsumerLifecycleListener {

    private final ObjectMapper objectMapper;
    private final MessageSendTransactionService transactionService;
    private final WorkerIdentity workerIdentity;
    private final MqConsumeMetrics mqConsumeMetrics;

    @Value("${notification.mq.dlq-consumer-group}")
    private String dlqConsumerGroup;

    @Override
    public void prepareStart(final DefaultMQPushConsumer consumer) {
        workerIdentity.configure(consumer, "notification-dlq");
    }

    @Override
    public void onMessage(final MessageExt message) {
        // 先计数再反序列化，毒消息即使无法解析也不能从 DLQ 指标中消失。
        mqConsumeMetrics.recordDlqReceived();

        String payload = new String(message.getBody(), StandardCharsets.UTF_8);

        NotificationSendEvent event = deserialize(payload);
        log.error(
                "收到通知死信，worker={}, eventId={}, messageId={}, mqMsgId={}, queueId={}, queueOffset={}, reconsumeTimes={}",
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

            // 已成功完成的消息不会被状态倒退；SENDING 才会被标记为 DEAD
            transactionService.finishDeadLetter(event, dlqConsumerGroup);
            log.error("通知死信已落库，eventId={}, messageId={}",
                    event.eventId(),
                    event.messageId()
            );
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
