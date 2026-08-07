package com.tam.notification.controller;

import com.tam.notification.common.web.ApiResponse;
import com.tam.notification.domain.application.Application;
import com.tam.notification.dto.CreateApplicationRequest;
import com.tam.notification.service.ApplicationService;
import com.tam.notification.vo.ApplicationResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/applications")
@RequiredArgsConstructor
public class ApplicationController {

    private final ApplicationService applicationService;

    @PostMapping
    public ApiResponse<ApplicationResponse> create(@Valid @RequestBody CreateApplicationRequest request) {
        Application application = applicationService.create(request.appCode(), request.appName());
        return ApiResponse.success(ApplicationResponse.from(application));
    }
}
