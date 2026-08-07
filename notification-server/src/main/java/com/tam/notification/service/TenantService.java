package com.tam.notification.service;

import com.tam.notification.common.exception.BusinessException;
import com.tam.notification.common.exception.CommonErrorCode;
import com.tam.notification.domain.tenant.Tenant;
import com.tam.notification.domain.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TenantService {
    private final TenantRepository tenantRepository;

    public Tenant create(String tenantCode, String tenantName) {
        tenantRepository.findByCode(tenantCode)
                .ifPresent(existing -> {
                    throw new BusinessException(CommonErrorCode.BUSINESS_ERROR, "租户编码已经存在");
                });

        Tenant tenant = new Tenant();
        tenant.setTenantCode(tenantCode);
        tenant.setTenantName(tenantName);
        tenant.setStatus(1);
        return tenantRepository.save(tenant);
    }

    public Tenant get(Long id) {
        return tenantRepository.findById(id)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.BUSINESS_ERROR, "租户不存在"));
    }

    public void update(Long id, String tenantCode, String tenantName) {
        Tenant tenant = tenantRepository.findById(id)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.BUSINESS_ERROR, "租户不存在"));
        tenant.setTenantCode(tenantCode);
        tenant.setTenantName(tenantName);
        tenantRepository.update(tenant);
    }

    public void delete(final Long id) {
        tenantRepository.deleteById(id);
    }
}
