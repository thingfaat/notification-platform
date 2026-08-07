package com.tam.notification.controller;

import com.tam.notification.common.web.ApiResponse;
import com.tam.notification.domain.template.MessageTemplate;
import com.tam.notification.dto.CreateMessageTemplateRequest;
import com.tam.notification.service.MessageTemplateService;
import com.tam.notification.vo.MessageTemplateResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

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

    @GetMapping
    public ApiResponse<MessageTemplateResponse> get(@RequestParam Long id) {
        MessageTemplate template = messageTemplateService.get(id);
        return ApiResponse.success(MessageTemplateResponse.from(template));
    }

    @PutMapping
    public ApiResponse<MessageTemplateResponse> update(@RequestParam Long id, @Valid @RequestBody CreateMessageTemplateRequest request) {
        messageTemplateService.update(id, request.applicationId(), request.templateCode(), request.templateName(),
                request.channelType(), request.templateContent(), request.variableSchema());
        return ApiResponse.success(null);
    }

    @DeleteMapping
    public ApiResponse<Void> delete(@RequestParam Long id) {
        messageTemplateService.delete(id);
        return ApiResponse.success(null);
    }
}
