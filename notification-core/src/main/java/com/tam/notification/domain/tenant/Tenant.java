package com.tam.notification.domain.tenant;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Tenant {
    private Long id;
    private String tenantCode;
    private String tenantName;
    private Integer status;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
