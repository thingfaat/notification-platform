package com.tam.notification.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("short_link_daily_visitor")
public class ShortLinkDailyVisitorDO {

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long tenantId;

    private Long shortLinkId;

    private LocalDate statDate;

    private String visitorKey;

    private LocalDateTime firstClickedAt;

    private LocalDateTime createdAt;
}
