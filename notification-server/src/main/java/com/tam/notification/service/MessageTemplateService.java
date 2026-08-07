package com.tam.notification.service;

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
        messageTemplateRepository.findByTenantIdAndApplicationIdAndTemplateCode(tenantId, applicationId, templateCode)
                .ifPresent(existing -> {
                    throw new RuntimeException("Message template already exists");
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
}
