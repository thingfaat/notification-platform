package com.tam.notification.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tam.notification.persistence.entity.ShortLinkDailyStatDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ShortLinkDailyStatMapper extends BaseMapper<ShortLinkDailyStatDO> {

    @Insert("""
            INSERT INTO short_link_click_stat_daily
            (
                id,
                tenant_id,
                short_link_id,
                stat_date,
                pv,
                uv
            )
            VALUES
            (
                #{id},
                #{tenantId},
                #{shortLinkId},
                #{statDate},
                #{pvDelta},
                #{uvDelta}
            )
            ON DUPLICATE KEY UPDATE
                pv = pv + #{pvDelta},
                uv = uv + #{uvDelta},
                updated_at = CURRENT_TIMESTAMP(3)
            """)
    int incrementDaily(
            @Param("id") Long id,
            @Param("tenantId") Long tenantId,
            @Param("shortLinkId") Long shortLinkId,
            @Param("statDate") LocalDate statDate,
            @Param("pvDelta") long pvDelta,
            @Param("uvDelta") long uvDelta
    );

    @Select("""
            SELECT
                id,
                tenant_id,
                short_link_id,
                stat_date,
                pv,
                uv,
                created_at,
                updated_at
            FROM short_link_click_stat_daily
            WHERE short_link_id = #{shortLinkId}
              AND stat_date BETWEEN #{startDate}
                                AND #{endDate}
            ORDER BY stat_date
            """)
    List<ShortLinkDailyStatDO> selectDaily(
            @Param("shortLinkId") Long shortLinkId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );
}
