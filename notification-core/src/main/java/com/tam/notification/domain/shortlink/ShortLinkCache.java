package com.tam.notification.domain.shortlink;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

public interface ShortLinkCache {

    Optional<ShortLinkCacheEntry> get(String shortCode);

    /**
     * 批量读取缓存。、
     * 返回结果只包含命中的短码；未命中或缓存故障路由由调用方回源数据库
     */
    Map<String, ShortLinkCacheEntry> getAll(
            Collection<String> shortCodes
    );

    void put(
            String shortCode,
            ShortLinkCacheEntry entry,
            Duration ttl
    );

    void evict(String shortCode);
}
