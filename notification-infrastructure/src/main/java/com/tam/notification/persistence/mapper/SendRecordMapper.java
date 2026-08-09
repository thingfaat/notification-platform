package com.tam.notification.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tam.notification.persistence.entity.SendRecordDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface SendRecordMapper extends BaseMapper<SendRecordDO> {
    @Update("""
            UPDATE notify_send_record
            SET
                send_status = 'SUCCESS',
                provider_message_id = #{providerMessageId},
                finished_at = #{finishedAt}
            WHERE id = #{id}
              AND send_status = 'PROCESSING'
            """)
    int markSuccess(@Param("id") Long id,
                    @Param("providerMessageId") String providerMessageId,
                    @Param("finishedAt") LocalDateTime finishedAt
    );

    @Update("""
            UPDATE notify_send_record
            SET
                send_status = 'FAILED',
                failure_code = #{failureCode},
                failure_reason = #{failureReason},
                finished_at = #{finishedAt}
            WHERE id = #{id}
              AND send_status = 'PROCESSING'
            """)
    int markFailed(@Param("id") Long id,
                   @Param("failureCode") String failureCode,
                   @Param("failureReason") String failureReason,
                   @Param("finishedAt") LocalDateTime finishedAt
    );
}
