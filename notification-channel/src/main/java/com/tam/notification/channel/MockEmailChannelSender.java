package com.tam.notification.channel;

import com.tam.notification.domain.enums.ChannelType;
import org.springframework.stereotype.Component;

@Component
public class MockEmailChannelSender extends AbstractMockChannelSender {

    @Override
    public ChannelType channelType() {
        return ChannelType.EMAIL;
    }
}
