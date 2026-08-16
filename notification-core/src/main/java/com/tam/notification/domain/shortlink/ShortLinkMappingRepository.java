package com.tam.notification.domain.shortlink;

import java.util.List;
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
     * 幂等重放需要根据已有的shortLinkId找回第一次创建的shortCode。
     * 普通租户会自动带 tenantId
     *
     * @param shortLinkId
     * @return
     */
    Optional<ShortLinkMapping> findByShortLinkId(Long shortLinkId);

    /**
     * 公共跳转入口在查询前没有租户上下文，因此通过全局唯一的短码跨租户查询路由映射
     *
     * @param shortCode
     * @return
     */
    Optional<ShortLinkMapping> findByShortCodeAcrossTenants(String shortCode);

    /**
     * 仅用于 Bloom 完整快照，普通租户业务禁止调用
     *
     * @return
     */
    List<String> findAllActiveShortCodesAcrossTenants();
}
