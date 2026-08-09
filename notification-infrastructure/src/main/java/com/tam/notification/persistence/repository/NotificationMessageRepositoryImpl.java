package com.tam.notification.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tam.notification.common.exception.BusinessException;
import com.tam.notification.common.exception.CommonErrorCode;
import com.tam.notification.domain.enums.MessageStatus;
import com.tam.notification.domain.message.NotificationMessage;
import com.tam.notification.domain.message.NotificationMessageRepository;
import com.tam.notification.persistence.entity.NotificationMessageDO;
import com.tam.notification.persistence.mapper.NotificationMessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class NotificationMessageRepositoryImpl implements NotificationMessageRepository {
    private final NotificationMessageMapper messageMapper;
    private final ObjectMapper objectMapper;

    @Override
    public NotificationMessage save(NotificationMessage message) {
        NotificationMessageDO data = toDO(message);
        messageMapper.insert(data);
        message.setId(data.getId());
        message.setTenantId(data.getTenantId());
        return message;
    }

    @Override
    public Optional<NotificationMessage> findById(final Long id) {
        return Optional.ofNullable(messageMapper.selectById(id))
                .map(this::toDomain);
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
        final var affectedRows = messageMapper.updateById(toDO(message));
        if (affectedRows != 1) {
            throw new IllegalStateException("消息状态已被其他线程修改，messageId = " + message.getId());
        }
        // 当前对象继续使用的话，同步内存version
        if (message.getVersion() != null) {
            message.setVersion(message.getVersion() + 1);
        }
    }

    @Override
    public List<NotificationMessage> findRetryableAcrossTenants(final int limit, final LocalDateTime now) {
        return messageMapper.selectRetryableAcrossTenants(limit, now)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public boolean requeueIfDue(final Long id, final LocalDateTime now) {
        return messageMapper.requeueIfDue(id, now) == 1;
    }

    @Override
    public boolean requeueDead(final Long id) {
        return messageMapper.requeueDead(id) == 1;
    }

    private NotificationMessageDO toDO(NotificationMessage message) {
        NotificationMessageDO data = new NotificationMessageDO();
        data.setId(message.getId());
        data.setTenantId(message.getTenantId());
        data.setTaskId(message.getTaskId());
        data.setMessageNo(message.getMessageNo());
        data.setReceiver(message.getReceiver());
        data.setTemplateParams(serializeParams(message.getTemplateParams()));
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
        message.setTemplateParams(deserializeParams(data.getTemplateParams()));
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

    private String serializeParams(Map<String, Object> params) {
        try {
            return objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            throw new BusinessException(CommonErrorCode.BUSINESS_ERROR, "参数序列化失败");
        }
    }

    private Map<String, Object> deserializeParams(String params) {
        try {
            return objectMapper.readValue(params, Map.class);
        } catch (Exception e) {
            throw new BusinessException(CommonErrorCode.BUSINESS_ERROR, "参数反序列化失败");
        }
    }
}
