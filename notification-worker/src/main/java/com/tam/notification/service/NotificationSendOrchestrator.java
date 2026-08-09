package com.tam.notification.service;

import com.tam.notification.channel.ChannelSenderRouter;
import com.tam.notification.domain.channel.ChannelSendCommand;
import com.tam.notification.domain.channel.ChannelSendResult;
import com.tam.notification.domain.channel.ChannelSendResultType;
import com.tam.notification.domain.outbox.NotificationSendEvent;
import com.tam.notification.model.PreparedSend;
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
    // 通道发送路由
    private final ChannelSenderRouter channelSenderRouter;

    @Value("${notification.mq.consumer-group}")
    private String consumerGroup;

    public void send(NotificationSendEvent event) {
        Optional<PreparedSend> optional = transactionService.prepare(event, consumerGroup);

        // mq重复投递，但是event已经完整执行
        if (optional.isEmpty()) {
            return;
        }

        PreparedSend prepared = optional.get();
        final var sender = channelSenderRouter.route(prepared.channelType());

        // 注意，不catch这里的未知异常，如果调用渠道出现timeout、connection reset等无法确认结果的未知异常，直接抛给rocketmq，rocketmq重新投递同一个event，prepare()会恢复原PROCESSING attempt
        // 因而使用同一个idempotencyKey
        ChannelSendResult result = sender.send(new ChannelSendCommand(prepared.messageId(), prepared.attemptNo(), prepared.idempotencyKey(), prepared.channelType(), prepared.receiver(), prepared.content()));

        if (result.type() == ChannelSendResultType.SUCCESS) {
            transactionService.finishSuccess(event, consumerGroup, prepared, result);
            return;
        }

        transactionService.finishFailure(event, consumerGroup, prepared, result);
    }
}
