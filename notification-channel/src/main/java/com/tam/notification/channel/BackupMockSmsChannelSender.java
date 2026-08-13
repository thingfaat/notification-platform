package com.tam.notification.channel;

import com.tam.notification.domain.enums.ChannelType;
import org.springframework.stereotype.Component;

/**
 * 备用短信渠道
 */
@Component
public class BackupMockSmsChannelSender extends AbstractMockChannelSender {
    @Override
    public ChannelType channelType() {
        return ChannelType.SMS;
    }

    @Override
    public String providerCode() {
        return "mock-sms-backup";
    }

    @Override
    public int priority() {
        return 200;
    }
}
