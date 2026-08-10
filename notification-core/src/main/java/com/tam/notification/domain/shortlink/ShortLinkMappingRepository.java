package com.tam.notification.domain.shortlink;

import java.util.Optional;

public interface ShortLinkMappingRepository {
    /**
     * 尝试占用短码
     *
     * @param mapping
     * @return
     */
    boolean trySave(ShortLinkMapping mapping);

    /**
     * 公共跳转入口在查询前没有租户上下文，因此通过全局唯一的短码跨租户查询路由映射
     *
     * @param shortCode
     * @return
     */
    Optional<ShortLinkMapping> findByShortCodeAcrossTenants(String shortCode);
}
