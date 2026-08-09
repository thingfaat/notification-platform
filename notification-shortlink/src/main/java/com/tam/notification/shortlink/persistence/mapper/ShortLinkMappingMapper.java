package com.tam.notification.shortlink.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tam.notification.shortlink.persistence.entity.ShortLinkMappingDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ShortLinkMappingMapper extends BaseMapper<ShortLinkMappingDO> {

    @Insert("""
            insert ignore into short_link_mapping
                   (
                   id,
                   tenant_id,
                   short_link_id,
                   short_code
                   )
                   values 
                   (
                   #{id},
                   #{tenantId},
                   #{shortLinkId},
                   #{shortCode}
                   )
            """)
    int insertIgnore(ShortLinkMappingDO data);
}
