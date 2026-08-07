package com.tam.notification.service;

import com.tam.notification.common.exception.BusinessException;
import com.tam.notification.common.exception.CommonErrorCode;
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
        applicationRepository.findByAppCode(appCode)
                .ifPresent(existing -> {
                    throw new BusinessException(CommonErrorCode.BUSINESS_ERROR, "应用编码已经存在");
                });

        Application application = new Application();
        application.setTenantId(tenantId);
        application.setAppCode(appCode);
        application.setAppName(appName);
        application.setStatus(1);
        return applicationRepository.save(application);
    }

    public Application get(Long id) {
        return applicationRepository.findById(id)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.BUSINESS_ERROR, "应用不存在"));
    }

    public void update(Long id, String appCode, String appName) {
        Application application = applicationRepository.findById(id)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.BUSINESS_ERROR, "应用不存在"));
        application.setAppCode(appCode);
        application.setAppName(appName);
        applicationRepository.update(application);
    }

    public void delete(Long id) {
        applicationRepository.deleteById(id);
    }
}
