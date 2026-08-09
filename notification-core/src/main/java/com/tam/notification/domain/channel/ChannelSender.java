package com.tam.notification.domain.channel;

import com.tam.notification.domain.enums.ChannelType;

public interface ChannelSender {
    ChannelType channelType();

    ChannelSendResult send(ChannelSendCommand command);
}
