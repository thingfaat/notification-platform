package com.tam.notification.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tam.notification.persistence.mapper.MessageTemplateMapper;
import com.tam.notification.domain.template.MessageTemplate;
import com.tam.notification.persistence.entity.MessageTemplateDO;
import com.tam.notification.domain.template.MessageTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class MessageTemplateRepositoryImpl implements MessageTemplateRepository {

    private final MessageTemplateMapper messageTemplateMapper;

    @Override
    public MessageTemplate save(final MessageTemplate template) {
        MessageTemplateDO data = toDO(template);
        messageTemplateMapper.insert(data);
        template.setId(data.getId());
        return template;
    }

    @Override
    public Optional<MessageTemplate> findById(final Long id) {
        MessageTemplateDO data = messageTemplateMapper.selectById(id);
        return Optional.ofNullable(data).map(this::toDomain);
    }

    @Override
    public Optional<MessageTemplate> findByTemplateCode(final Long applicationId, final String templateCode) {
        MessageTemplateDO data = messageTemplateMapper.selectOne(
                Wrappers.<MessageTemplateDO>lambdaQuery()
                        .eq(MessageTemplateDO::getApplicationId, applicationId)
                        .eq(MessageTemplateDO::getTemplateCode, templateCode));
        return Optional.ofNullable(data).map(this::toDomain);
    }

    @Override
    public void update(final MessageTemplate template) {
        messageTemplateMapper.updateById(toDO(template));
    }

    @Override
    public void deleteById(final Long id) {
        messageTemplateMapper.deleteById(id);
    }

    private MessageTemplateDO toDO(final MessageTemplate template) {
        MessageTemplateDO data = new MessageTemplateDO();
        data.setId(template.getId());
        data.setTenantId(template.getTenantId());
        data.setApplicationId(template.getApplicationId());
        data.setTemplateCode(template.getTemplateCode());
        data.setTemplateName(template.getTemplateName());
        data.setChannelType(template.getChannelType());
        data.setTemplateContent(template.getTemplateContent());
        data.setVariableSchema(template.getVariableSchema());
        data.setStatus(template.getStatus());
        data.setVersion(template.getVersion());
        data.setCreatedAt(template.getCreatedAt());
        data.setUpdatedAt(template.getUpdatedAt());
        return data;
    }

    private MessageTemplate toDomain(final MessageTemplateDO data) {
        MessageTemplate template = new MessageTemplate();
        template.setId(data.getId());
        template.setTenantId(data.getTenantId());
        template.setApplicationId(data.getApplicationId());
        template.setTemplateCode(data.getTemplateCode());
        template.setTemplateName(data.getTemplateName());
        template.setChannelType(data.getChannelType());
        template.setTemplateContent(data.getTemplateContent());
        template.setVariableSchema(data.getVariableSchema());
        template.setStatus(data.getStatus());
        template.setVersion(data.getVersion());
        template.setCreatedAt(data.getCreatedAt());
        template.setUpdatedAt(data.getUpdatedAt());
        return template;
    }
}
