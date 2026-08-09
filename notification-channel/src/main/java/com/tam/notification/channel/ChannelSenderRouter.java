package com.tam.notification.channel;

import com.tam.notification.domain.channel.ChannelSender;
import com.tam.notification.domain.enums.ChannelType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class ChannelSenderRouter {

    private final Map<ChannelType, ChannelSender> senderMap;

    public ChannelSenderRouter(List<ChannelSender> senders) {
        EnumMap<ChannelType, ChannelSender> map = new EnumMap<>(ChannelType.class);
        for (ChannelSender sender : senders) {
            ChannelSender old = map.put(sender.channelType(), sender);
            if (old != null) {
                throw new IllegalStateException("渠道Sender重复注册: " + sender.channelType());
            }
        }
        this.senderMap = Map.copyOf(map);
    }

    public ChannelSender route(ChannelType channelType) {
        final var sender = senderMap.get(channelType);
        if (sender == null) {
            throw new IllegalStateException("未找到渠道Sender：" + channelType);
        }
        return sender;
    }
}
