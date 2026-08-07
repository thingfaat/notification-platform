package com.tam.notification.controller;

import com.tam.notification.common.web.ApiResponse;
import com.tam.notification.domain.application.Application;
import com.tam.notification.dto.CreateApplicationRequest;
import com.tam.notification.service.ApplicationService;
import com.tam.notification.vo.ApplicationResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

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

    @GetMapping
    public ApiResponse<ApplicationResponse> get(@RequestParam Long id) {
        Application application = applicationService.get(id);
        return ApiResponse.success(ApplicationResponse.from(application));
    }

    @PutMapping
    public ApiResponse<ApplicationResponse> update(@RequestParam Long id, @Valid @RequestBody CreateApplicationRequest request) {
        applicationService.update(id, request.appCode(), request.appName());
        return ApiResponse.success(null);
    }

    @DeleteMapping
    public ApiResponse<Void> delete(@RequestParam Long id) {
        applicationService.delete(id);
        return ApiResponse.success(null);
    }
}
