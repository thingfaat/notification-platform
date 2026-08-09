package com.tam.notification.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tam.notification.domain.outbox.ConsumeRecordRepository;
import com.tam.notification.persistence.entity.ConsumeRecordDO;
import com.tam.notification.persistence.mapper.ConsumeRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ConsumeRecordRepositoryImpl implements ConsumeRecordRepository {

    private final ConsumeRecordMapper consumeRecordMapper;

    @Override
    public boolean exists(final Long tenantId, final String consumerGroup, final String eventId) {
        Long count = consumeRecordMapper.selectCount(Wrappers.<ConsumeRecordDO>lambdaQuery()
                .eq(ConsumeRecordDO::getTenantId, tenantId)
                .eq(ConsumeRecordDO::getConsumerGroup, consumerGroup)
                .eq(ConsumeRecordDO::getEventId, eventId)
        );
        return count > 0;
    }

    @Override
    public boolean tryCreate(final Long tenantId, final String consumerGroup, final String eventId, final Long messageId) {
        ConsumeRecordDO entity = new ConsumeRecordDO();
        // 自定义insert，显示生成id最稳妥
        entity.setId(IdWorker.getId());
        entity.setTenantId(tenantId);
        entity.setConsumerGroup(consumerGroup);
        entity.setEventId(eventId);
        entity.setMessageId(messageId);
        return consumeRecordMapper.insertIgnore(entity) > 0;
    }
}
