package com.tam.notification.mq;

import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rocketmq.producer", name = "group")
public class RocketMQEventPublisher {
    private final RocketMQTemplate rocketMQTemplate;

    public void publish(String topic, String payload) {
        final var sendResult = rocketMQTemplate.syncSend(topic, payload, 3000);
        if (sendResult == null || sendResult.getSendStatus() != SendStatus.SEND_OK) {
            throw new IllegalStateException("消息发送失败");
        }
    }
}
