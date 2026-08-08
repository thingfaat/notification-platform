package com.tam.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.List;

public record CreateNotificationTaskRequest(
        @NotBlank(message = "requestId不能为空")
        String requestId,
        @NotNull(message = "applicationId不能为空")
        Long applicationId,
        @NotNull(message = "templateId不能为空")
        Long templateId,
        LocalDateTime scheduleTime,
        @NotEmpty(message = "接收人列表不能为空")
        List<RecipientRequest> recipients
) {
}
