package com.tam.notification.domain.template;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MessageTemplate {
    private Long id;
    private Long tenantId;
    private Long applicationId;
    private String templateCode;
    private String templateName;
    private String channelType;
    private String templateContent;
    private String variableSchema;
    private Integer status;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
