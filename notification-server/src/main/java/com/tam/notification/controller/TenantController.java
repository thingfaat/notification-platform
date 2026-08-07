package com.tam.notification.controller;

import com.tam.notification.common.web.ApiResponse;
import com.tam.notification.domain.tenant.Tenant;
import com.tam.notification.dto.CreateTenantRequest;
import com.tam.notification.service.TenantService;
import com.tam.notification.vo.TenantResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
public class TenantController {

    private final TenantService tenantService;

    @PostMapping
    public ApiResponse<TenantResponse> create(@Valid @RequestBody CreateTenantRequest request) {
        Tenant tenant = tenantService.create(request.tenantCode(), request.tenantName());
        return ApiResponse.success(TenantResponse.from(tenant));
    }
}
