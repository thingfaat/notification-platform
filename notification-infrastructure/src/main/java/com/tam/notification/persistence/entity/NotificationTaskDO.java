package com.tam.notification.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("notify_task")
public class NotificationTaskDO {
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

    private Long applicationId;

    private String requestId;

    private Long templateId;

    private String channelType;

    private String taskStatus;

    private LocalDateTime scheduleTime;

    private Integer totalCount;

    private Integer successCount;

    private Integer failedCount;
}
