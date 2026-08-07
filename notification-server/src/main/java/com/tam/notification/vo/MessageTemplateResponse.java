package com.tam.notification.vo;

import com.tam.notification.domain.template.MessageTemplate;
import lombok.Data;

@Data
public class MessageTemplateResponse {
    private Long id;
    private Long tenantId;
    private Long applicationId;
    private String templateCode;
    private String templateName;
    private String channelType;
    private String templateContent;
    private String variableSchema;
    private Integer status;

    public static MessageTemplateResponse from(final MessageTemplate template) {
        MessageTemplateResponse response = new MessageTemplateResponse();
        response.setId(template.getId());
        response.setTenantId(template.getTenantId());
        response.setApplicationId(template.getApplicationId());
        response.setTemplateCode(template.getTemplateCode());
        response.setTemplateName(template.getTemplateName());
        response.setChannelType(template.getChannelType());
        response.setTemplateContent(template.getTemplateContent());
        response.setVariableSchema(template.getVariableSchema());
        response.setStatus(template.getStatus());
        return response;
    }
}
