package com.tam.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateChannelAccountRequest(

        @NotNull(message = "应用ID不能为空")
        Long applicationId,

        @NotBlank(message = "渠道账号编码不能为空")
        String accountCode,

        @NotBlank(message = "渠道账号名称不能为空")
        String accountName,

        @NotBlank(message = "渠道类型不能为空")
        String channelType,

        String provider,

        String configJson
) {
}
