package com.tam.notification.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tam.notification.persistence.entity.ShortLinkMappingDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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


    @InterceptorIgnore(tenantLine = "1")
    @Select("""
            select 
                id,
                tenant_id,
                short_link_id,
                short_code,
                created_at
            from short_link_mapping
            where short_code = #{shortCode}
            limit 1
            """)
    ShortLinkMappingDO selectByShortCodeAcrossTenants(@Param("shortCode") String shortCode);
}
