package com.tam.notification.model;

import com.tam.notification.domain.enums.ChannelType;

public record PreparedSend(
        Long sendRecordId,
        Long messageId,
        Integer attemptNo,
        String idempotencyKey,
        ChannelType channelType,
        String receiver,
        String content
) {
}
