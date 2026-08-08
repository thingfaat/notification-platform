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
            return;
        }

        // 查询Message
        NotificationMessage message = messageRepository.findById(event.messageId()).orElseThrow(() -> new IllegalStateException("Message不存在"));

        // 解决publisher与consumer竞态
        if (message.getMessageStatus() == MessageStatus.CREATED) {
            message.changeStatus(MessageStatus.QUEUED);
        }

        // 开始真正进入worker处理
        if (message.getMessageStatus() == MessageStatus.QUEUED) {
            message.changeStatus(MessageStatus.SENDING);
        }

        // todo...
        log.info("开始处理消息: {}", message);

        // 保存
        messageRepository.update(message);
    }
}
