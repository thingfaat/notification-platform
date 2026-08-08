package com.tam.notification.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("notify_message")
public class NotificationMessageDO {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    @Version
    private Integer version;

    // @TableLogic
    // private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Long taskId;

    private String messageNo;

    private String receiver;

    private String templateParams;

    private String renderedContent;

    private String messageStatus;

    private Integer retryCount;

    private LocalDateTime nextRetryTime;

    private String providerMessageId;

    private String failureCode;

    private String failureReason;
}
