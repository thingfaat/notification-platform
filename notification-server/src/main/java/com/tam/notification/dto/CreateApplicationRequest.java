package com.tam.notification.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateApplicationRequest(

        @NotBlank(message = "应用编码不能为空")
        String appCode,

        @NotBlank(message = "应用名称不能为空")
        String appName
) {
}