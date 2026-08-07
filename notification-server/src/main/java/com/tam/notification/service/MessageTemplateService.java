package com.tam.notification.service;

import com.tam.notification.common.exception.BusinessException;
import com.tam.notification.common.exception.CommonErrorCode;
import com.tam.notification.common.tenant.TenantContext;
import com.tam.notification.domain.template.MessageTemplate;
import com.tam.notification.domain.template.MessageTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MessageTemplateService {
    private final MessageTemplateRepository messageTemplateRepository;

    public MessageTemplate create(Long applicationId, String templateCode, String templateName,
                                  String channelType, String templateContent, String variableSchema) {
        Long tenantId = TenantContext.requireTenantId();
        messageTemplateRepository.findByTemplateCode(tenantId, applicationId, templateCode)
                .ifPresent(existing -> {
                    throw new BusinessException(CommonErrorCode.BUSINESS_ERROR, "模板编码已经存在");
                });

        MessageTemplate template = new MessageTemplate();
        template.setTenantId(tenantId);
        template.setApplicationId(applicationId);
        template.setTemplateCode(templateCode);
        template.setTemplateName(templateName);
        template.setChannelType(channelType);
        template.setTemplateContent(templateContent);
        template.setVariableSchema(variableSchema);
        template.setStatus(1);
        return messageTemplateRepository.save(template);
    }

    public MessageTemplate get(Long id) {
        return messageTemplateRepository.findById(id)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.BUSINESS_ERROR, "消息模板不存在"));
    }

    public void update(Long id, Long applicationId, String templateCode, String templateName,
                       String channelType, String templateContent, String variableSchema) {
        MessageTemplate template = messageTemplateRepository.findById(id)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.BUSINESS_ERROR, "消息模板不存在"));
        template.setApplicationId(applicationId);
        template.setTemplateCode(templateCode);
        template.setTemplateName(templateName);
        template.setChannelType(channelType);
        template.setTemplateContent(templateContent);
        template.setVariableSchema(variableSchema);
        messageTemplateRepository.update(template);
    }

    public void delete(Long id) {
        messageTemplateRepository.deleteById(id);
    }
}
