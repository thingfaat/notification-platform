package com.tam.notification.domain.outbox;

public enum OutboxStatus {
    NEW,
    PUBLISHED,
    FAILED,
    DEAD
}
