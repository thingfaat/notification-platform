package com.tam.notification.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tam.notification.persistence.entity.OutboxEventDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OutboxEventMapper extends BaseMapper<OutboxEventDO> {
    /**
     * 查询待处理的事件，包含：
     * 1. 新事件
     * 2. 失败事件
     * 3. 锁定事件
     *
     * @param limit
     * @param claimExpiredBefore
     * @return
     */
    @InterceptorIgnore(tenantLine = "1")
    @Select("""
            select
                id,
                tenant_id,
                event_id,
                aggregate_type,
                aggregate_id,
                event_type,
                topic,
                payload,
                publish_status,
                retry_count,
                next_retry_time,
                locked_by,
                locked_at,
                last_error,
                published_at,
                created_at,
                updated_at
            from notify_outbox
            where
                publish_status = 'NEW'
                or
                (
                    publish_status = 'FAILED'
                    and
                    (
                        next_retry_time is null
                        or next_retry_time <= NOW(3)
                    )
                )
                or
                (
                    publish_status = 'PROCESSING'
                    and locked_at <= #{claimExpiredBefore}
                )
            order by created_at asc
            limit #{limit}
            """)
    List<OutboxEventDO> selectClaimableAcrossTenants(@Param("limit") int limit, @Param("claimExpiredBefore") LocalDateTime claimExpiredBefore);

    /**
     * 尝试获取锁
     *
     * @param id
     * @param lockOwner          锁所有者
     * @param claimExpiredBefore 锁过期时间
     * @return
     */
    @Update("""
            update notify_outbox
            set
                publish_status = 'PROCESSING',
                locked_by = #{lockOwner},
                locked_at = NOW(3)
            where id = #{id}
              and
              (
                  publish_status = 'NEW'
                  or
                  (
                      publish_status = 'FAILED'
                      and
                      (
                          next_retry_time is null
                          or next_retry_time <= NOW(3)
                      )
                  )
                  or
                  (
                      publish_status = 'PROCESSING'
                      and locked_at <= #{claimExpiredBefore}
                  )
              )
            """)
    int tryClaim(@Param("id") Long id, @Param("lockOwner") String lockOwner, @Param("claimExpiredBefore") LocalDateTime claimExpiredBefore);

    /**
     * 更新消息状态为已发布
     *
     * @param id
     * @param lockOwner
     * @return
     */
    @Update("""
            UPDATE notify_outbox
            SET
                publish_status = 'PUBLISHED',
                published_at = NOW(3),
                locked_by = NULL,
                locked_at = NULL,
                next_retry_time = NULL,
                last_error = NULL
            WHERE id = #{id}
              AND publish_status = 'PROCESSING'
              AND locked_by = #{lockOwner}
            """)
    int markPublished(@Param("id") Long id, @Param("lockOwner") String lockOwner);

    /**
     * 更新消息状态为失败
     *
     * @param id
     * @param lockOwner
     * @param targetStatus
     * @param retryCount
     * @param nextRetryTime
     * @param error
     * @return
     */
    @Update("""
            UPDATE notify_outbox
            SET
                publish_status = #{targetStatus},
                retry_count = #{retryCount},
                next_retry_time = #{nextRetryTime},
                last_error = #{error},
                locked_by = NULL,
                locked_at = NULL
            WHERE id = #{id}
              AND publish_status = 'PROCESSING'
              AND locked_by = #{lockOwner}
            """)
    int markFailed(@Param("id") Long id,
                   @Param("lockOwner") String lockOwner,
                   @Param("targetStatus") String targetStatus,
                   @Param("retryCount") int retryCount,
                   @Param("nextRetryTime")
                   LocalDateTime nextRetryTime,
                   @Param("error") String error);
}
