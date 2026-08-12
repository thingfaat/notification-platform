package com.tam.notification.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("short_link_click_stat_daily")
public class ShortLinkDailyStatDO {

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long tenantId;

    private Long shortLinkId;

    private LocalDate statDate;

    private Long pv;

    private Long uv;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
