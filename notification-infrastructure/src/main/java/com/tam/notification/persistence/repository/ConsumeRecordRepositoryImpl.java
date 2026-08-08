package com.tam.notification.persistence.repository;

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
    public boolean tryCreate(final Long tenantId, final String consumerGroup, final String eventId, final Long messageId) {
        ConsumeRecordDO entity = new ConsumeRecordDO();
        entity.setTenantId(tenantId);
        entity.setConsumerGroup(consumerGroup);
        entity.setEventId(eventId);
        entity.setMessageId(messageId);
        return consumeRecordMapper.insert(entity) > 0;
    }
}
