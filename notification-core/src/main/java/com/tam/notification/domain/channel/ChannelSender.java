package com.tam.notification.domain.channel;

import com.tam.notification.domain.enums.ChannelType;

public interface ChannelSender {

    /**
     * 渠道类型
     *
     * @return
     */
    ChannelType channelType();

    /**
     * 同一渠道下用于区分具体供应商
     *
     * @return
     */
    String providerCode();

    /**
     * 数字越小，路由优先级越高
     *
     * @return
     */
    int priority();

    /**
     * 发送消息
     *
     * @param command
     * @return
     */
    ChannelSendResult send(ChannelSendCommand command);
}
