package com.tam.notification.domain.shortlink;

import java.time.Duration;
import java.util.Optional;

public interface ShortLinkCache {

    Optional<ShortLinkCacheEntry> get(String shortCode);

    void put(
            String shortCode,
            ShortLinkCacheEntry entry,
            Duration ttl
    );

    void evict(String shortCode);
}
