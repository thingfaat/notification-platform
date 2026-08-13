package com.tam.notification.channel;

import com.tam.notification.domain.enums.ChannelType;
import org.springframework.stereotype.Component;

/**
 * 模拟短信渠道
 */
@Component
public class MockSmsChannelSender extends AbstractMockChannelSender {

    @Override
    public ChannelType channelType() {
        return ChannelType.SMS;
    }

    @Override
    public String providerCode() {
        return "mock-sms-primary";
    }

    @Override
    public int priority() {
        return 100;
    }
}
