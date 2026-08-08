package com.tam.notification.domain.enums;

/**
 * 消息状态
 */
public enum MessageStatus {

    CREATED,
    QUEUED,
    SENDING,

    SENT,
    DELIVERED,
    DELIVERY_FAILED,

    RETRY_WAIT,
    DEAD,

    CANCELLED
}
