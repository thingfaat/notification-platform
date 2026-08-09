package com.tam.notification.domain.message;

import com.tam.notification.domain.enums.MessageStatus;

import java.util.Map;
import java.util.Set;

public final class MessageStateMachine {
    private static final Map<MessageStatus, Set<MessageStatus>> TRANSITIONS = Map.of(
            MessageStatus.CREATED, Set.of(MessageStatus.QUEUED, MessageStatus.CANCELLED),
            MessageStatus.QUEUED, Set.of(MessageStatus.SENDING, MessageStatus.CANCELLED),
            MessageStatus.SENDING, Set.of(MessageStatus.SENT, MessageStatus.RETRY_WAIT, MessageStatus.DEAD),
            MessageStatus.RETRY_WAIT, Set.of(MessageStatus.QUEUED, MessageStatus.DEAD),
            MessageStatus.SENT, Set.of(MessageStatus.DELIVERED, MessageStatus.DELIVERY_FAILED),

            // 人工重试，支持重新入队
            MessageStatus.DEAD, Set.of(MessageStatus.QUEUED)
    );

    private MessageStateMachine() {
    }

    public static void checkTransition(MessageStatus current, MessageStatus target) {
        Set<MessageStatus> targets = TRANSITIONS.getOrDefault(current, Set.of());
        if (!targets.contains(target)) {
            throw new IllegalStateException(String.format("非法消息状态转换：%s, %s", current, target));
        }
    }
}
