package com.tam.notification.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("notify_template")
@Data
public class MessageTemplateDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    private Long applicationId;

    private String templateCode;

    private String templateName;

    private String channelType;

    private String templateContent;

    private String variableSchema;

    private Integer status;

    @TableLogic
    private Integer deleted;

    @Version
    private Integer version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
