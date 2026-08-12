package com.tam.notification.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tam.notification.persistence.entity.ShortLinkDailyVisitorDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ShortLinkDailyVisitorMapper extends BaseMapper<ShortLinkDailyVisitorDO> {

    @Insert("""
            INSERT IGNORE INTO short_link_daily_visitor
            (
                id,
                tenant_id,
                short_link_id,
                stat_date,
                visitor_key,
                first_clicked_at
            )
            VALUES
            (
                #{id},
                #{tenantId},
                #{shortLinkId},
                #{statDate},
                #{visitorKey},
                #{firstClickedAt}
            )
            """)
    int insertIgnore(ShortLinkDailyVisitorDO data);
}
