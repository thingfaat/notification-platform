package com.tam.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateMessageTemplateRequest(
        @NotNull(message = "应用ID不能为空")
        Long applicationId,

        @NotBlank(message = "模板编码不能为空")
        String templateCode,

        @NotBlank(message = "模板名称不能为空")
        String templateName,

        @NotBlank(message = "渠道类型不能为空")
        String channelType,

        @NotBlank(message = "模板内容不能为空")
        String templateContent,

        String variableSchema
) {
}
