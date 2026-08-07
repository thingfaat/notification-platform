package com.tam.notification.vo;

import com.tam.notification.domain.tenant.Tenant;
import lombok.Data;

@Data
public class TenantResponse {
    private Long id;
    private String tenantCode;
    private String tenantName;
    private Integer status;

    public static TenantResponse from(final Tenant tenant) {
        TenantResponse response = new TenantResponse();
        response.setId(tenant.getId());
        response.setTenantCode(tenant.getTenantCode());
        response.setTenantName(tenant.getTenantName());
        response.setStatus(tenant.getStatus());
        return response;
    }
}
