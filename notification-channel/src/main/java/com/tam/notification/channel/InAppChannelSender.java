package com.tam.notification.channel;

import com.tam.notification.domain.enums.ChannelType;
import org.springframework.stereotype.Component;

/**
 * 站内信渠道
 */
@Component
public class InAppChannelSender extends AbstractMockChannelSender {

    @Override
    public ChannelType channelType() {
        return ChannelType.IN_APP;
    }

    @Override
    public String providerCode() {
        return "in-app-primary";
    }

    @Override
    public int priority() {
        return 100;
    }
}
