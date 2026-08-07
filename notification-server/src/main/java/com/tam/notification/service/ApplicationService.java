package com.tam.notification.service;

import com.tam.notification.common.tenant.TenantContext;
import com.tam.notification.domain.application.Application;
import com.tam.notification.domain.application.ApplicationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ApplicationService {
    private final ApplicationRepository applicationRepository;

    public Application create(String appCode, String appName) {
        Long tenantId = TenantContext.requireTenantId();
        applicationRepository.findByTenantIdAndAppCode(tenantId, appCode)
                .ifPresent(existing -> {
                    throw new RuntimeException("Application already exists");
                });

        Application application = new Application();
        application.setTenantId(tenantId);
        application.setAppCode(appCode);
        application.setAppName(appName);
        application.setStatus(1);
        return applicationRepository.save(application);
    }
}
