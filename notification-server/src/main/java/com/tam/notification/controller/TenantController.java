package com.tam.notification.controller;

import com.tam.notification.common.web.ApiResponse;
import com.tam.notification.domain.tenant.Tenant;
import com.tam.notification.dto.CreateTenantRequest;
import com.tam.notification.service.TenantService;
import com.tam.notification.vo.TenantResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

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

    @GetMapping
    public ApiResponse<TenantResponse> get(@RequestParam Long id) {
        Tenant tenant = tenantService.get(id);
        return ApiResponse.success(TenantResponse.from(tenant));
    }

    @PutMapping
    public ApiResponse<TenantResponse> update(@RequestParam Long id, @Valid @RequestBody CreateTenantRequest request) {
        tenantService.update(id, request.tenantCode(), request.tenantName());
        return ApiResponse.success(null);
    }

    @DeleteMapping
    public ApiResponse<Void> delete(@RequestParam Long id) {
        tenantService.delete(id);
        return ApiResponse.success(null);
    }
}
