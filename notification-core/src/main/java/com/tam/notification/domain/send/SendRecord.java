package com.tam.notification.domain.send;

import com.tam.notification.domain.enums.ChannelType;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SendRecord {
    private Long id;

    private Long tenantId;

    private Long messageId;

    private String eventId;

    private Integer attemptNo;

    private ChannelType channelType;

    private String idempotencyKey;

    private SendRecordStatus sendStatus;

    private String providerMessageId;

    private String failureCode;

    private String failureReason;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
