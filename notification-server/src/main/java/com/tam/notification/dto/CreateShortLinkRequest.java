package com.tam.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public record CreateShortLinkRequest(

        @NotBlank(message = "requestId不能为空")
        @Size(max = 64, message = "requestId长度不能超过64个字符")
        String requestId,

        @NotNull(message = "applicationId不能为空")
        @Positive(message = "applicationId必须大于0")
        Long applicationId,

        @NotBlank(message = "原始URL不能为空")
        @Size(
                max = 2048,
                message = "原始URL长度不能超过2048个字符"
        )
        String originalUrl,

        /**
         * 不在Controller使用@Future，Service必须先查询已有幂等结果，再决定新建请求是否要求未来时间；
         * 否则原请求过期后的幂等重放会在到达Service前被错误拒绝
         * @Future(message = "过期时间必须晚于当前时间")
         */
        @NotNull(message = "过期时间不能为空")
        LocalDateTime expireAt
) {
}
