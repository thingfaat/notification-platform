package com.tam.notification.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("sys_tenant")
@Data
public class TenantDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String tenantCode;

    private String tenantName;

    private Integer status;

    @TableLogic
    private Integer deleted;

    @Version
    private Integer version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
