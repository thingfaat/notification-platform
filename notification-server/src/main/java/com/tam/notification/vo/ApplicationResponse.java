package com.tam.notification.vo;

import com.tam.notification.domain.application.Application;
import lombok.Data;

@Data
public class ApplicationResponse {
    private Long id;
    private Long tenantId;
    private String appCode;
    private String appName;
    private Integer status;

    public static ApplicationResponse from(final Application application) {
        ApplicationResponse response = new ApplicationResponse();
        response.setId(application.getId());
        response.setTenantId(application.getTenantId());
        response.setAppCode(application.getAppCode());
        response.setAppName(application.getAppName());
        response.setStatus(application.getStatus());
        return response;
    }
}
