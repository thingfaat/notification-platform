package com.tam.notification.common.tenant;

public class TenantContext {
    private static final ThreadLocal<Long> CONTEXT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void setTenantId(Long tenantId) {
        CONTEXT.set(tenantId);
    }

    public static Long getTenantId() {
        return CONTEXT.get();
    }

    public static Long requireTenantId() {

        Long tenantId = CONTEXT.get();

        if (tenantId == null) {
            throw new IllegalStateException(
                    "Tenant context is missing"
            );
        }

        return tenantId;
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
