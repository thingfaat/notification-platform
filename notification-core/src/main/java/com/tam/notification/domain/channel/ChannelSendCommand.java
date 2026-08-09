package com.tam.notification.domain.channel;

import com.tam.notification.domain.enums.ChannelType;

public record ChannelSendCommand (
        Long messageId,
        Integer attemptNo,
        String idempotencyKey,
        ChannelType channelType,
        String receiver,
        String content
) {
}
