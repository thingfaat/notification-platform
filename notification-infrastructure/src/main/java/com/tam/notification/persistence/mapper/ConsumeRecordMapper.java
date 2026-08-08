package com.tam.notification.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tam.notification.persistence.entity.ConsumeRecordDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConsumeRecordMapper extends BaseMapper<ConsumeRecordDO> {
    @Insert("""
            INSERT IGNORE INTO notify_consume_record
            (
                id,
                tenant_id,
                consumer_group,
                event_id,
                message_id
            )
            VALUES
            (
                #{id},
                #{tenantId},
                #{consumerGroup},
                #{eventId},
                #{messageId}
            )
            """)
    int insertIgnore(ConsumeRecordDO entity);
}
