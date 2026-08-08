package com.tam.notification.service;

import com.tam.notification.domain.enums.MessageStatus;
import com.tam.notification.domain.message.NotificationMessage;
import com.tam.notification.domain.message.NotificationMessageRepository;
import com.tam.notification.domain.outbox.ConsumeRecordRepository;
import com.tam.notification.domain.outbox.NotificationSendEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationConsumeService {
    private static final String CONSUMER_GROUP = "notification-worker-group";
    private final ConsumeRecordRepository consumeRecordRepository;
    private final NotificationMessageRepository messageRepository;

    @Transactional
    public void consume(NotificationSendEvent event) {
        // 先抢消费资格
        boolean firstConsume = consumeRecordRepository.tryCreate(event.tenantId(), CONSUMER_GROUP, event.eventId(), event.messageId());
        if (!firstConsume) {
            // 重复消费
            log.info("重复MQ消息，直接忽略, eventId={}", event.eventId());
            return;
        }

        // 查询Message
        NotificationMessage message = messageRepository.findById(event.messageId()).orElseThrow(() -> new IllegalStateException("Message不存在"));

        // worker只负责 queued->sending
        if (message.getMessageStatus() != MessageStatus.QUEUED) {
            throw new IllegalStateException("消息状态不是QUEUED，无法开始发送：" + message.getMessageStatus());
        }

        // 开始真正进入worker处理
        message.changeStatus(MessageStatus.SENDING);

        // 保存
        messageRepository.update(message);

        log.info("消息进入发送阶段, messageId={}, eventId={}", message.getId(), event.eventId());
        /*
         * Day 6：
         *
         * ChannelRouter
         * ↓
         * ChannelSender
         * ↓
         * SENT / RETRY_WAIT / DEAD
         */
    }
}
