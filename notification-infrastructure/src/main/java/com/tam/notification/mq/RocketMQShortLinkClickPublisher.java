package com.tam.notification.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tam.notification.domain.shortlink.ShortLinkClickEvent;
import com.tam.notification.domain.shortlink.ShortLinkClickEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * rocket mq异步发布器
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "rocketmq.producer",
        name = "group"
)
public class RocketMQShortLinkClickPublisher implements ShortLinkClickEventPublisher {

    private final RocketMQTemplate rocketMQTemplate;

    private final ObjectMapper objectMapper;

    @Value("${notification.shortlink.click.topic}")
    private String topic;

    @Value("${notification.shortlink.click.send-timeout-ms:1000}")
    private long sendTimeoutMills;

    @Override
    public void publish(final ShortLinkClickEvent event) {
        String payload = serialize(event);

        Message<String> message = MessageBuilder.withPayload(payload)
                .setHeader(RocketMQHeaders.KEYS, event.eventId())
                .build();

        rocketMQTemplate.asyncSend(
                topic,
                message,
                new SendCallback() {
                    @Override
                    public void onSuccess(final SendResult result) {
                        if (result == null
                                || result.getSendStatus() != SendStatus.SEND_OK) {
                            log.warn(
                                    "short-link click async send returned abnormal result, eventId={}, result={}",
                                    event.eventId(),
                                    result
                            );
                        }
                    }

                    @Override
                    public void onException(final Throwable e) {
                        log.warn(
                                "short-link click async send failed, eventId={}",
                                event.eventId(),
                                e
                        );
                    }
                },
                sendTimeoutMills
        );
    }

    private String serialize(ShortLinkClickEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "serialize short-link click event failed",
                    exception
            );
        }
    }
}
