package com.tam.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record RecipientRequest(
        @NotBlank(message = "接收人不能为空")
        String receiver,
        @NotNull(message = "模板参数不能为空")
        Map<String, Object> params
) {
}
