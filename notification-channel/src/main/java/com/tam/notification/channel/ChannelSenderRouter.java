package com.tam.notification.channel;

import com.tam.notification.domain.channel.ChannelSender;
import com.tam.notification.domain.enums.ChannelType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ChannelSenderRouter {

    private final Map<ChannelType, List<ChannelSender>> senderMap;

    public ChannelSenderRouter(List<ChannelSender> senders) {
        EnumMap<ChannelType, List<ChannelSender>> grouped = new EnumMap<>(ChannelType.class);

        Set<SenderRegistration> registrations = new HashSet<>();
        Set<PriorityRegistration> priorities = new HashSet<>();

        for (ChannelSender sender : senders) {
            if (sender.channelType() == null) {
                throw new IllegalStateException("渠道channelType不能为空");
            }

            if (sender.providerCode() == null || sender.providerCode().isBlank()) {
                throw new IllegalStateException("渠道providerCode不能为空");
            }

            SenderRegistration registration = new SenderRegistration(
                    sender.channelType(),
                    sender.providerCode()
            );
            if (!registrations.add(registration)) {
                throw new IllegalStateException("渠道Sender重复注册: " + registration);
            }

            PriorityRegistration priorityRegistration = new PriorityRegistration(
                    sender.channelType(),
                    sender.priority()
            );
            if (!priorities.add(priorityRegistration)) {
                throw new IllegalStateException(
                        "同一渠道不能注册相同priority: " + priorityRegistration
                );
            }

            // 如果不存在则创建，然后加入
            grouped.computeIfAbsent(
                    sender.channelType(),
                    ignored -> new ArrayList<>()
            ).add(sender);
        }

        EnumMap<ChannelType, List<ChannelSender>> sorted = new EnumMap<>(ChannelType.class);

        // 数字越小，路由优先级越高；同一渠道的priority已在启动时保证唯一。
        grouped.forEach((channelType, candidates) -> {
            candidates.sort(Comparator.comparingInt(ChannelSender::priority));
            sorted.put(
                    channelType,
                    List.copyOf(candidates)
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

    private record SenderRegistration(
            ChannelType channelType,
            String providerCode
    ) {
    }

    private record PriorityRegistration(
            ChannelType channelType,
            int priority
    ) {
    }
}
