package com.tam.notification.service;

import com.tam.notification.domain.channel.ChannelSendCommand;
import com.tam.notification.domain.channel.ChannelSendResult;
import com.tam.notification.domain.channel.ChannelSendResultType;
import com.tam.notification.domain.outbox.NotificationSendEvent;
import com.tam.notification.model.PreparedSend;
import com.tam.notification.resilience.ResilientChannelSendService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationSendOrchestrator {
    // 消息发送事务服务
    private final MessageSendTransactionService transactionService;
    // 弹性渠道发送服务
    private final ResilientChannelSendService channelSendService;
    // 渠道限流服务
    private final NotificationRateLimitService rateLimitService;

    @Value("${notification.mq.consumer-group}")
    private String consumerGroup;

    /**
     * 发送消息，组织本地事务和渠道发送，
     *
     * @param event
     */
    public void send(NotificationSendEvent event) {
        /**
         * 限流必须在prepare之前
         * 被限流时不进入SENDING，也不创建SendRecord
         */
        if (!rateLimitService.allowOrDefer(event)) {
            return;
        }

        Optional<PreparedSend> optional = transactionService.prepare(event, consumerGroup);

        // mq重复投递，但是event已经完整执行
        if (optional.isEmpty()) {
            return;
        }

        // 获取发送通道
        PreparedSend prepared = optional.get();
        ChannelSendResult result = channelSendService.send(
                new ChannelSendCommand(
                        prepared.messageId(),
                        prepared.attemptNo(),
                        prepared.idempotencyKey(),
                        prepared.channelType(),
                        prepared.receiver(),
                        prepared.content()
                )
        );

        // 发送成功
        if (result.type() == ChannelSendResultType.SUCCESS) {
            transactionService.finishSuccess(
                    event,
                    consumerGroup,
                    prepared,
                    result
            );

            return;
        }

        transactionService.finishFailure(
                event,
                consumerGroup,
                prepared,
                result
        );
    }
}
