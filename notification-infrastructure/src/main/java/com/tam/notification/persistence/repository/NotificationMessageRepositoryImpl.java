package com.tam.notification.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tam.notification.domain.enums.MessageStatus;
import com.tam.notification.domain.message.NotificationMessage;
import com.tam.notification.domain.message.NotificationMessageRepository;
import com.tam.notification.persistence.entity.NotificationMessageDO;
import com.tam.notification.persistence.mapper.NotificationMessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class NotificationMessageRepositoryImpl implements NotificationMessageRepository {
    private final NotificationMessageMapper messageMapper;

    @Override
    public NotificationMessage save(NotificationMessage message) {
        NotificationMessageDO data = toDO(message);
        messageMapper.insert(data);
        message.setId(data.getId());
        message.setTenantId(data.getTenantId());
        return message;
    }

    @Override
    public List<NotificationMessage> findByTaskId(Long taskId) {
        return messageMapper.selectList(Wrappers.<NotificationMessageDO>lambdaQuery()
                        .eq(NotificationMessageDO::getTaskId, taskId)
                        .orderByAsc(NotificationMessageDO::getId))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public void update(NotificationMessage message) {
        messageMapper.updateById(toDO(message));
    }

    private NotificationMessageDO toDO(NotificationMessage message) {
        NotificationMessageDO data = new NotificationMessageDO();
        data.setId(message.getId());
        data.setTenantId(message.getTenantId());
        data.setTaskId(message.getTaskId());
        data.setMessageNo(message.getMessageNo());
        data.setReceiver(message.getReceiver());
        data.setTemplateParams(message.getTemplateParams());
        data.setRenderedContent(message.getRenderedContent());

        if (message.getMessageStatus() != null) {
            data.setMessageStatus(message.getMessageStatus().name());
        }

        data.setRetryCount(message.getRetryCount());
        data.setNextRetryTime(message.getNextRetryTime());
        data.setProviderMessageId(message.getProviderMessageId());
        data.setFailureCode(message.getFailureCode());
        data.setFailureReason(message.getFailureReason());
        data.setVersion(message.getVersion());
        return data;
    }

    private NotificationMessage toDomain(NotificationMessageDO data) {
        NotificationMessage message = new NotificationMessage();
        message.setId(data.getId());
        message.setTenantId(data.getTenantId());
        message.setTaskId(data.getTaskId());
        message.setMessageNo(data.getMessageNo());
        message.setReceiver(data.getReceiver());
        message.setTemplateParams(data.getTemplateParams());
        message.setRenderedContent(data.getRenderedContent());
        message.setMessageStatus(MessageStatus.valueOf(data.getMessageStatus()));
        message.setRetryCount(data.getRetryCount());
        message.setNextRetryTime(data.getNextRetryTime());
        message.setProviderMessageId(data.getProviderMessageId());
        message.setFailureCode(data.getFailureCode());
        message.setFailureReason(data.getFailureReason());
        message.setVersion(data.getVersion());
        return message;
    }
}
