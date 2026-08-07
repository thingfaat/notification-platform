package com.tam.notification.mybatis;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.tam.notification.common.tenant.TenantContext;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

@Component
public class TenantMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        if (!metaObject.hasSetter("tenantId")) {
            return;
        }

        Long tenantId = TenantContext.requireTenantId();

        this.strictInsertFill(
                metaObject,
                "tenantId",
                Long.class,
                tenantId
        );
    }

    @Override
    public void updateFill(MetaObject metaObject) {
    }
}
