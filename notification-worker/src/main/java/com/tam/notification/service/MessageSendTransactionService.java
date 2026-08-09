package com.tam.notification.service;

import com.tam.notification.domain.channel.ChannelSendResult;
import com.tam.notification.domain.channel.ChannelSendResultType;
import com.tam.notification.domain.enums.MessageStatus;
import com.tam.notification.domain.message.NotificationMessage;
import com.tam.notification.domain.message.NotificationMessageRepository;
import com.tam.notification.domain.outbox.ConsumeRecordRepository;
import com.tam.notification.domain.outbox.NotificationSendEvent;
import com.tam.notification.domain.send.SendRecord;
import com.tam.notification.domain.send.SendRecordRepository;
import com.tam.notification.domain.send.SendRecordStatus;
import com.tam.notification.domain.task.NotificationTask;
import com.tam.notification.domain.task.NotificationTaskRepository;
import com.tam.notification.model.PreparedSend;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 发送消息事务
 */
@Service
@RequiredArgsConstructor
public class MessageSendTransactionService {
    private final NotificationMessageRepository messageRepository;
    private final NotificationTaskRepository taskRepository;
    private final SendRecordRepository sendRecordRepository;
    private final ConsumeRecordRepository consumeRecordRepository;

    @Value("${notification.send.max-attempts:3}")
    private int maxAttempts;

    /**
     * 准备一次发送
     *
     * @param event
     * @param consumerGroup
     * @return
     */
    @Transactional
    public Optional<PreparedSend> prepare(NotificationSendEvent event, String consumerGroup) {
        // event已经完整执行完毕
        if (consumeRecordRepository.exists(event.tenantId(), consumerGroup, event.eventId())) {
            return Optional.empty();
        }

        NotificationMessage message = messageRepository.findById(event.messageId()).orElseThrow(() -> new IllegalStateException("Message不存在：" + event.messageId()));
        NotificationTask task = taskRepository.findById(message.getTaskId()).orElseThrow(() -> new IllegalStateException("Task不存在：" + message.getTaskId()));
        int attemptNo = message.getRetryCount() + 1;

        // mq重新投递，上一次prepare已经提交，但是外部调用或最终落库还没有完成，必须继续使用原来的attempt
        if (message.getMessageStatus() == MessageStatus.SENDING) {
            SendRecord sendRecord = sendRecordRepository.findByMessageIdAndAttemptNo(message.getId(), attemptNo).orElseThrow(() -> new IllegalStateException("SENDING消息不存在PROCESSING发送记录：" + message.getId()));
            if (sendRecord.getSendStatus() != SendRecordStatus.PROCESSING) {
                throw new IllegalStateException("发送记录状态异常：" + sendRecord.getSendStatus());
            }
            return Optional.of(buildPreparedSend(message, task, sendRecord));
        }

        if (message.getMessageStatus() != MessageStatus.QUEUED) {
            throw new IllegalStateException("Message当前状态无法发送：" + message.getMessageStatus());
        }

        // queue -> sending
        message.changeStatus(MessageStatus.SENDING);
        // 乐观锁保证多个consumer只有一个真正进入发送阶段
        messageRepository.update(message);

        SendRecord sendRecord = new SendRecord();
        sendRecord.setMessageId(message.getId());
        sendRecord.setEventId(event.eventId());
        sendRecord.setAttemptNo(attemptNo);
        sendRecord.setChannelType(task.getChannelType());
        sendRecord.setIdempotencyKey(buildIdempotencyKey(message, attemptNo));
        sendRecord.setSendStatus(SendRecordStatus.PROCESSING);
        sendRecord.setStartedAt(LocalDateTime.now());
        sendRecordRepository.save(sendRecord);
        return Optional.of(buildPreparedSend(message, task, sendRecord));
    }

    /**
     * 发送成功时，更新记录
     *
     * @param event
     * @param consumerGroup
     * @param prepared
     * @param result
     */
    @Transactional
    public void finishSuccess(NotificationSendEvent event, String consumerGroup, PreparedSend prepared, ChannelSendResult result) {
        boolean firstFinish = consumeRecordRepository.tryCreate(event.tenantId(), consumerGroup, event.eventId(), event.messageId());
        if (!firstFinish) {
            return;
        }

        NotificationMessage message = messageRepository.findById(prepared.messageId()).orElseThrow(() -> new IllegalStateException("Message不存在：" + prepared.messageId()));
        if (message.getMessageStatus() != MessageStatus.SENDING) {
            throw new IllegalStateException("完成发送时Message状态异常：" + message.getMessageStatus());
        }
        message.setProviderMessageId(result.providerMessageId());
        message.setFailureCode(null);
        message.setFailureReason(null);
        message.setNextRetryTime(null);
        message.changeStatus(MessageStatus.SENT);
        messageRepository.update(message);

        boolean success = sendRecordRepository.markSuccess(prepared.sendRecordId(), result.providerMessageId(), LocalDateTime.now());
        if (!success) {
            throw new IllegalStateException("完成发送时更新发送记录异常");
        }
    }

    /**
     * 发送失败时，更新记录
     *
     * @param event
     * @param consumerGroup
     * @param prepared
     * @param result
     */
    @Transactional
    public void finishFailure(NotificationSendEvent event, String consumerGroup, PreparedSend prepared, ChannelSendResult result) {
        boolean firstFinish = consumeRecordRepository.tryCreate(event.tenantId(), consumerGroup, event.eventId(), event.messageId());
        if (!firstFinish) {
            return;
        }

        NotificationMessage message = messageRepository.findById(prepared.messageId()).orElseThrow(() -> new IllegalStateException("Message不存在：" + prepared.messageId()));
        if (message.getMessageStatus() != MessageStatus.SENDING) {
            throw new IllegalStateException("完成发送时Message状态异常：" + message.getMessageStatus());
        }

        int retryCount = message.getRetryCount() + 1;
        message.setRetryCount(retryCount);
        message.setFailureCode(result.errorCode());
        message.setFailureReason(result.errorMessage());
        boolean retryable = result.type() == ChannelSendResultType.RETRYABLE_FAILURE && retryCount < maxAttempts;

        if (retryable) { // 可重试的失败
            message.changeStatus(MessageStatus.RETRY_WAIT);
            message.setNextRetryTime(LocalDateTime.now().plusSeconds(calculateDelay(retryCount)));
        } else {
            message.changeStatus(MessageStatus.DEAD);
            message.setNextRetryTime(null);
        }
        messageRepository.update(message);

        boolean success = sendRecordRepository.markFailed(prepared.sendRecordId(), result.errorCode(), result.errorMessage(), LocalDateTime.now());
        if (!success) {
            throw new IllegalStateException("完成发送时更新发送记录异常");
        }
    }

    /**
     * 死信队列处理
     *
     * @param event
     * @param dlqConsumerGroup
     */
    @Transactional
    public void finishDeadLetter(NotificationSendEvent event, String dlqConsumerGroup) {
        final var firstConsume = consumeRecordRepository.tryCreate(event.tenantId(), dlqConsumerGroup, event.eventId(), event.messageId());
        if (!firstConsume) {
            return;
        }

        NotificationMessage message = messageRepository.findById(event.messageId()).orElseThrow(() -> new IllegalStateException("Message不存在：" + event.messageId()));

        // 如果正常Consumer已经成功完成，DLQ消息只记录消费，不在倒退状态
        if (message.getMessageStatus() != MessageStatus.SENDING) {
            return;
        }

        int attemptNo = message.getRetryCount() + 1;
        final var sendRecord = sendRecordRepository.findByMessageIdAndAttemptNo(message.getId(), attemptNo).orElseThrow(() -> new IllegalStateException("DLQ消息不存在PROCESSING发送记录：" + message.getId()));

        if (sendRecord.getSendStatus() != SendRecordStatus.PROCESSING) {
            throw new IllegalStateException("DLQ发送记录状态异常：" + sendRecord.getId());
        }
        String failureCode = "MQ_RECONSUME_EXHAUSTED";
        String failureReason = "RocketMQ消费重试耗尽，消息进入DLQ";

        message.setRetryCount(message.getRetryCount() + 1);
        message.setFailureCode(failureCode);
        message.setFailureReason(failureReason);
        message.setNextRetryTime(null);
        message.changeStatus(MessageStatus.DEAD);
        messageRepository.update(message);

        boolean recordUpdated = sendRecordRepository.markFailed(
                sendRecord.getId(),
                failureCode,
                failureReason,
                LocalDateTime.now()
        );

        if (!recordUpdated) {
            throw new IllegalStateException("DLQ发送记录更新失败：" + sendRecord.getId());
        }
    }

    private PreparedSend buildPreparedSend(NotificationMessage message, NotificationTask task, SendRecord sendRecord) {
        return new PreparedSend(sendRecord.getId(), message.getId(), sendRecord.getAttemptNo(), sendRecord.getIdempotencyKey(), task.getChannelType(), message.getReceiver(), message.getRenderedContent());
    }

    private String buildIdempotencyKey(NotificationMessage message, int attemptNo) {
        return message.getMessageNo() + ":" + attemptNo;
    }

    private long calculateDelay(int retryCount) {
        return Math.min(60, 5L << Math.min(retryCount - 1, 4));
    }
}
