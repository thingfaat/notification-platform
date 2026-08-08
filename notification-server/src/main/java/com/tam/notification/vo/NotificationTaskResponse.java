package com.tam.notification.vo;

import com.tam.notification.domain.task.NotificationTask;

import java.time.LocalDateTime;

public record NotificationTaskResponse(
        Long id,
        String requestId,
        Long applicationId,
        Long templateId,
        String channelType,
        String taskStatus,
        LocalDateTime scheduleTime,
        Integer totalCount,
        Integer successCount,
        Integer failedCount
) {
    public static NotificationTaskResponse from(NotificationTask task) {
        return new NotificationTaskResponse(
                task.getId(),
                task.getRequestId(),
                task.getApplicationId(),
                task.getTemplateId(),
                task.getChannelType().name(),
                task.getTaskStatus().name(),
                task.getScheduleTime(),
                task.getTotalCount(),
                task.getSuccessCount(),
                task.getFailedCount()
        );
    }
}
