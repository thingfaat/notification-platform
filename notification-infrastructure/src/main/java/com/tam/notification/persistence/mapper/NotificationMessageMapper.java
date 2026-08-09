package com.tam.notification.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tam.notification.persistence.entity.NotificationMessageDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface NotificationMessageMapper extends BaseMapper<NotificationMessageDO> {
    @InterceptorIgnore(tenantLine = "1")
    @Select("""
            SELECT
                id,
                tenant_id,
                task_id,
                message_no,
                receiver,
                template_params,
                rendered_content,
                message_status,
                retry_count,
                next_retry_time,
                provider_message_id,
                failure_code,
                failure_reason,
                version,
                created_at,
                updated_at
            FROM notify_message
            WHERE message_status = 'RETRY_WAIT'
              AND next_retry_time <= #{now}
            ORDER BY next_retry_time ASC
            LIMIT #{limit}
            """)
    List<NotificationMessageDO> selectRetryableAcrossTenants(@Param("limit") int limit, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE notify_message
            SET
                message_status = 'QUEUED',
                next_retry_time = NULL,
                version = version + 1
            WHERE id = #{id}
              AND message_status = 'RETRY_WAIT'
              AND next_retry_time <= #{now}
            """)
    int requeueIfDue(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE notify_message
            SET
                message_status = 'QUEUED',
                next_retry_time = NULL,
                version = version + 1
            WHERE id = #{id}
              AND message_status = 'DEAD'
            """)
    int requeueDead(@Param("id") Long id);
}
