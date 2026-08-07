package com.tam.notification.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateTenantRequest(
        @NotBlank(message = "租户编码不能为空")
        String tenantCode,

        @NotBlank(message = "租户名称不能为空")
        String tenantName
) {
}
