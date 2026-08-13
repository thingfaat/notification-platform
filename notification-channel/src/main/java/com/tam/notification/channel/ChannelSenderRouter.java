package com.tam.notification.channel;

import com.tam.notification.domain.channel.ChannelSender;
import com.tam.notification.domain.enums.ChannelType;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class ChannelSenderRouter {

    private final Map<ChannelType, List<ChannelSender>> senderMap;

    public ChannelSenderRouter(List<ChannelSender> senders) {
        EnumMap<ChannelType, List<ChannelSender>> grouped = new EnumMap<>(ChannelType.class);

        Set<String> registrations = new HashSet<>();
        for (ChannelSender sender : senders) {
            if (sender.providerCode() == null || sender.providerCode().isBlank()) {
                throw new IllegalStateException("渠道providerCode不能为空");
            }

            // 使用通知类型+供应商代码作为注册，如果重复则抛出异常
            String registration = sender.channelType() + ":" + sender.providerCode();
            if (!registrations.add(registration)) {
                throw new IllegalStateException("渠道Sender重复注册: " + registration);
            }

            // 如果不存在则创建，然后加入
            grouped.computeIfAbsent(
                    sender.channelType(),
                    ignored -> new ArrayList<>()
            ).add(sender);
        }

        EnumMap<ChannelType, List<ChannelSender>> sorted = new EnumMap<>(ChannelType.class);

        // 按优先级排序，排序后的顺序为：优先级高的在前，优先级一样的按照渠道类型排序
        grouped.forEach((channelType, candidates) -> {
            candidates.sort(Comparator.comparingInt(ChannelSender::priority).thenComparing(ChannelSender::channelType));
            sorted.put(
                    channelType,
                    candidates
            );
        });
        this.senderMap = Map.copyOf(sorted);
    }

    public ChannelSender route(ChannelType channelType) {
        return routeCandidates(channelType).get(0);
    }

    public List<ChannelSender> routeCandidates(final ChannelType channelType) {
        final var senders = senderMap.get(channelType);

        if (senders == null || senders.isEmpty()) {
            throw new IllegalStateException("未找到渠道Sender: " + channelType);
        }

        return senders;
    }
}
