package com.tam.notification.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tam.notification.domain.enums.ChannelType;
import com.tam.notification.domain.send.SendRecord;
import com.tam.notification.domain.send.SendRecordRepository;
import com.tam.notification.domain.send.SendRecordStatus;
import com.tam.notification.persistence.entity.SendRecordDO;
import com.tam.notification.persistence.mapper.SendRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class SendRecordRepositoryImpl implements SendRecordRepository {

    private final SendRecordMapper sendRecordMapper;

    @Override
    public SendRecord save(SendRecord sendRecord) {
        SendRecordDO data = toDO(sendRecord);
        sendRecordMapper.insert(data);
        sendRecord.setId(data.getId());
        sendRecord.setTenantId(data.getTenantId());
        return sendRecord;
    }

    @Override
    public Optional<SendRecord>
    findByMessageIdAndAttemptNo(Long messageId, Integer attemptNo) {

        SendRecordDO data = sendRecordMapper.selectOne(
                Wrappers.<SendRecordDO>lambdaQuery()
                        .eq(SendRecordDO::getMessageId, messageId)
                        .eq(SendRecordDO::getAttemptNo, attemptNo)
        );

        return Optional.ofNullable(data).map(this::toDomain);
    }

    @Override
    public boolean markSuccess(Long id, String providerMessageId, LocalDateTime finishedAt) {
        return sendRecordMapper.markSuccess(id, providerMessageId, finishedAt) == 1;
    }

    @Override
    public boolean markFailed(Long id, String failureCode, String failureReason, LocalDateTime finishedAt) {
        return sendRecordMapper.markFailed(id, failureCode, failureReason, finishedAt) == 1;
    }

    private SendRecordDO toDO(SendRecord record) {
        SendRecordDO data = new SendRecordDO();
        data.setId(record.getId());
        data.setTenantId(record.getTenantId());
        data.setMessageId(record.getMessageId());
        data.setEventId(record.getEventId());
        data.setAttemptNo(record.getAttemptNo());

        if (record.getChannelType() != null) {
            data.setChannelType(record.getChannelType().name());
        }

        data.setIdempotencyKey(record.getIdempotencyKey());

        if (record.getSendStatus() != null) {
            data.setSendStatus(record.getSendStatus().name());
        }

        data.setProviderMessageId(record.getProviderMessageId());
        data.setFailureCode(record.getFailureCode());
        data.setFailureReason(record.getFailureReason());
        data.setStartedAt(record.getStartedAt());
        data.setFinishedAt(record.getFinishedAt());
        return data;
    }

    private SendRecord toDomain(SendRecordDO data) {
        SendRecord record = new SendRecord();
        record.setId(data.getId());
        record.setTenantId(data.getTenantId());
        record.setMessageId(data.getMessageId());
        record.setEventId(data.getEventId());
        record.setAttemptNo(data.getAttemptNo());
        record.setChannelType(ChannelType.valueOf(data.getChannelType()));
        record.setIdempotencyKey(data.getIdempotencyKey());
        record.setSendStatus(SendRecordStatus.valueOf(data.getSendStatus()));
        record.setProviderMessageId(data.getProviderMessageId());
        record.setFailureCode(data.getFailureCode());
        record.setFailureReason(data.getFailureReason());
        record.setStartedAt(data.getStartedAt());
        record.setFinishedAt(data.getFinishedAt());
        record.setCreatedAt(data.getCreatedAt());
        record.setUpdatedAt(data.getUpdatedAt());
        return record;
    }
}
