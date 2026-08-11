package com.tam.notification.domain.shortlink;

import java.util.Collection;
import java.util.Optional;

/**
 * 防穿透抽象
 */
public interface ShortLinkProtection {

    Optional<ShortLinkNegativeReason> getNegative(String shortCode);

    void cacheNegative(
            String shortCode,
            ShortLinkNegativeReason reason
    );

    void evictNegative(String shortCode);

    /**
     * false表示一定不存在，true表示可能存在
     *
     * @param shortCode
     * @return
     */
    boolean mightContain(String shortCode);

    void addToBloom(String shortCode);

    boolean beginBloomRebuild();

    void completeBloomRebuild(Collection<String> shortCodes);
}

