package com.tam.notification.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("notify_channel_account")
public class ChannelAccountDO {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    @Version
    private Integer version;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Long applicationId;

    private String accountCode;

    private String accountName;

    private String channelType;

    private String provider;

    private String configJson;

    private Integer status;
}
