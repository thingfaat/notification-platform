package com.tam.notification.domain.outbox;

public enum OutboxStatus {
    NEW,
    /**
     * 已被某个publisher实例抢占，正在尝试放松rocket mq
     */
    PROCESSING,
    PUBLISHED,
    FAILED,
    DEAD
}
