package com.tam.notification.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("notify_send_record")
public class SendRecordDO {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    private Long messageId;

    private String eventId;

    private Integer attemptNo;

    private String channelType;

    private String idempotencyKey;

    private String sendStatus;

    private String providerMessageId;

    private String failureCode;

    private String failureReason;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
