package com.tam.notification.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tam.notification.persistence.entity.ShortLinkClickDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ShortLinkClickMapper extends BaseMapper<ShortLinkClickDO> {

    @Insert("""
            INSERT IGNORE INTO short_link_click
            (
                id,
                tenant_id,
                event_id,
                short_link_id,
                short_code,
                visitor_key,
                clicked_at
            )
            VALUES
            (
                #{id},
                #{tenantId},
                #{eventId},
                #{shortLinkId},
                #{shortCode},
                #{visitorKey},
                #{clickedAt}
            )
            """)
    int insertIgnore(ShortLinkClickDO data);
}
