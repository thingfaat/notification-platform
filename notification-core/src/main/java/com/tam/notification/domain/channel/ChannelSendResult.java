package com.tam.notification.domain.channel;

public record ChannelSendResult(
        ChannelSendResultType type,
        String providerMessageId,
        String errorCode,
        String errorMessage
) {
    public static ChannelSendResult success(String providerMessageId) {
        return new ChannelSendResult(ChannelSendResultType.SUCCESS, providerMessageId, null, null);
    }

    public static ChannelSendResult retryableFailure(String errorCode, String errorMessage) {
        return new ChannelSendResult(
                ChannelSendResultType.RETRYABLE_FAILURE,
                null,
                errorCode,
                errorMessage
        );
    }

    public static ChannelSendResult permanentFailure(String errorCode, String errorMessage) {
        return new ChannelSendResult(
                ChannelSendResultType.PERMANENT_FAILURE,
                null,
                errorCode,
                errorMessage
        );
    }
}
