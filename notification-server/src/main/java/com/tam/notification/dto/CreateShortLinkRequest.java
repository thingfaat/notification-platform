package com.tam.notification.dto;

import jakarta.validation.constraints.*;

import java.time.LocalDateTime;

public record CreateShortLinkRequest (
        @NotNull(message = "applicationId不能为空")
        @Positive(message = "applicationId必须大于0")
        Long applicationId,

        @NotBlank(message = "原始URL不能为空")
        @Size(
                max = 2048,
                message = "原始URL长度不能超过2048个字符"
        )
        String originalUrl,

        @NotNull(message = "过期时间不能为空")
        @Future(message = "过期时间必须晚于当前时间")
        LocalDateTime expireAt
) {
}
