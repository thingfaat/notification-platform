package com.tam.notification.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName("notify_consume_record")
public class ConsumeRecordDO {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    private String consumerGroup;

    private String eventId;

    private Long messageId;
}
