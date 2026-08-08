package com.tam.notification.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tam.notification.persistence.entity.OutboxEventDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface OutboxEventMapper extends BaseMapper<OutboxEventDO> {
    @InterceptorIgnore(tenantLine = "1")
    @Select("""
            select id, tenant_id, event_id, aggregate_type, aggregate_id, event_type, topic, payload, publish_status, retry_count, next_retry_time, last_error, published_at, created_at, updated_at
            from notify_outbox where ( publish_status = 'NEW' or (publish_status = 'FAILED' and (next_retry_time is null or next_retry_time <= now(3) ) ) ) order by created_at asc limit #{limit}
            """)
    List<OutboxEventDO> selectPendingAcrossTenants(@Param("limit") int limit);
}
