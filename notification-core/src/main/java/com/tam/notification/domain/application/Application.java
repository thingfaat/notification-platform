package com.tam.notification.domain.application;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Application {
    private Long id;
    private Long tenantId;
    private String appCode;
    private String appName;
    private Integer status;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
