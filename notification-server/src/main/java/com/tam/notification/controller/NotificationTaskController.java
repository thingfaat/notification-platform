package com.tam.notification.controller;

import com.tam.notification.common.web.ApiResponse;
import com.tam.notification.domain.task.NotificationTask;
import com.tam.notification.dto.CreateNotificationTaskRequest;
import com.tam.notification.service.NotificationTaskService;
import com.tam.notification.vo.NotificationTaskResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/notification-tasks")
@RequiredArgsConstructor
public class NotificationTaskController {

    private final NotificationTaskService notificationTaskService;

    @PostMapping
    public ApiResponse<NotificationTaskResponse> create(@Valid @RequestBody CreateNotificationTaskRequest request) {
        NotificationTask task = notificationTaskService.create(request);
        return ApiResponse.success(NotificationTaskResponse.from(task));
    }

    @GetMapping("{id}")
    public ApiResponse<NotificationTaskResponse> get(@PathVariable Long id) {
        NotificationTask task = notificationTaskService.get(id);
        return ApiResponse.success(NotificationTaskResponse.from(task));
    }
}
