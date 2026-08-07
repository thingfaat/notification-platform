package com.tam.notification.controller;

import com.tam.notification.common.web.ApiResponse;
import com.tam.notification.domain.template.MessageTemplate;
import com.tam.notification.dto.CreateMessageTemplateRequest;
import com.tam.notification.service.MessageTemplateService;
import com.tam.notification.vo.MessageTemplateResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/templates")
@RequiredArgsConstructor
public class MessageTemplateController {

    private final MessageTemplateService messageTemplateService;

    @PostMapping
    public ApiResponse<MessageTemplateResponse> create(@Valid @RequestBody CreateMessageTemplateRequest request) {
        MessageTemplate template = messageTemplateService.create(
                request.applicationId(), request.templateCode(), request.templateName(),
                request.channelType(), request.templateContent(), request.variableSchema());
        return ApiResponse.success(MessageTemplateResponse.from(template));
    }
}
