package com.tam.notification.persistence.repository;

import com.tam.notification.domain.outbox.OutboxEvent;
import com.tam.notification.domain.outbox.OutboxRepository;
import com.tam.notification.domain.outbox.OutboxStatus;
import com.tam.notification.persistence.entity.OutboxEventDO;
import com.tam.notification.persistence.mapper.OutboxEventMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class OutboxRepositoryImpl implements OutboxRepository {

    private final OutboxEventMapper outboxEventMapper;

    @Override
    public OutboxEvent save(final OutboxEvent event) {
        OutboxEventDO data = toDO(event);
        outboxEventMapper.insert(data);
        event.setId(data.getId());
        return event;
    }

    @Override
    public List<OutboxEvent> findClaimable(final int limit, LocalDateTime claimExpiredBefore) {
        return outboxEventMapper.selectClaimableAcrossTenants(limit, claimExpiredBefore)
                .stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public boolean tryClaim(final Long id, final String lockOwner, final LocalDateTime claimExpiredBefore) {
        return outboxEventMapper.tryClaim(id, lockOwner, claimExpiredBefore) == 1;
    }

    @Override
    public boolean markPublished(final Long id, final String lockOwner) {
        return outboxEventMapper.markPublished(id, lockOwner) == 1;
    }

    @Override
    public boolean markFailed(final Long id, String lockOwner, OutboxStatus targetStatus, final Integer retryCount, final LocalDateTime nextRetryTime, final String error) {
        return outboxEventMapper.markFailed(id, lockOwner, targetStatus.name(), retryCount, nextRetryTime, error) == 1;
    }

    private OutboxEventDO toDO(final OutboxEvent event) {
        OutboxEventDO data = new OutboxEventDO();
        data.setId(event.getId());
        data.setTenantId(event.getTenantId());
        data.setEventId(event.getEventId());
        data.setAggregateType(event.getAggregateType());
        data.setAggregateId(event.getAggregateId());
        data.setEventType(event.getEventType());
        data.setTopic(event.getTopic());
        data.setPayload(event.getPayload());
        data.setPublishStatus(event.getPublishStatus().name());
        data.setRetryCount(event.getRetryCount());
        data.setNextRetryTime(event.getNextRetryTime());
        data.setLastError(event.getLastError());
        data.setPublishedAt(event.getPublishedAt());
        data.setLockedBy(event.getLockedBy());
        data.setLockedAt(event.getLockedAt());
        return data;
    }

    private OutboxEvent toDomain(final OutboxEventDO data) {
        OutboxEvent event = new OutboxEvent();
        event.setId(data.getId());
        event.setTenantId(data.getTenantId());
        event.setEventId(data.getEventId());
        event.setAggregateType(data.getAggregateType());
        event.setAggregateId(data.getAggregateId());
        event.setEventType(data.getEventType());
        event.setTopic(data.getTopic());
        event.setPayload(data.getPayload());
        event.setPublishStatus(OutboxStatus.valueOf(data.getPublishStatus()));
        event.setRetryCount(data.getRetryCount());
        event.setNextRetryTime(data.getNextRetryTime());
        event.setLastError(data.getLastError());
        event.setPublishedAt(data.getPublishedAt());
        event.setLockedBy(data.getLockedBy());
        event.setLockedAt(data.getLockedAt());
        return event;
    }
}
