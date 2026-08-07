package com.tam.notification.service;

import com.tam.notification.domain.tenant.Tenant;
import com.tam.notification.domain.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TenantService {
    private final TenantRepository tenantRepository;

    public Tenant create(String tenantCode, String tenantName) {
        tenantRepository.findByCode(tenantCode)
                .ifPresent(existing -> {
                    throw new RuntimeException("Tenant already exists");
                });

        Tenant tenant = new Tenant();
        tenant.setTenantCode(tenantCode);
        tenant.setTenantName(tenantName);
        tenant.setStatus(1);
        return tenantRepository.save(tenant);
    }
}
