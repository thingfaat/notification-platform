package com.tam.notification.domain.channel;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ChannelAccount {
    private Long id;
    private Long tenantId;
    private Long applicationId;
    private String accountCode;
    private String accountName;
    private String channelType;
    private String provider;
    private String configJson;
    private Integer status;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
