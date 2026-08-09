package com.tam.notification.domain.channel;

public enum ChannelSendResultType {

    SUCCESS,

    // 渠道明确告诉我们：本次没有发送成功，但允许稍后重试
    RETRYABLE_FAILURE,

    // 明确失败，而且重试无意义，例如手机号格式错误
    PERMANENT_FAILURE,
}
