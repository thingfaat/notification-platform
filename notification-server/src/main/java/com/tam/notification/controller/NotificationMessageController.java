package com.tam.notification.controller;

import com.tam.notification.common.web.ApiResponse;
import com.tam.notification.service.NotificationManualRetryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/notification-messages")
@RequiredArgsConstructor
public class NotificationMessageController {

    private final NotificationManualRetryService manualRetryService;

    /**
     * 手动重试
     *
     * @param messageId
     * @return
     */
    @PostMapping("/{messageId}/retry")
    public ApiResponse<Void> retry(@PathVariable("messageId") Long messageId) {
        manualRetryService.retry(messageId);
        return ApiResponse.success(null);
    }
}
