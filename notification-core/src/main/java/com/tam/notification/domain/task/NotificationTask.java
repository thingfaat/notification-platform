package com.tam.notification.domain.task;

import com.tam.notification.domain.enums.TaskStatus;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class NotificationTask {
    private Long id;
    private Long tenantId;
    private Long applicationId;
    private String requestId;
    private Long templateId;
    private String channelType;
    private TaskStatus taskStatus;
    private LocalDateTime scheduleTime;
    private Integer totalCount;
    private Integer successCount;
    private Integer failedCount;

    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
