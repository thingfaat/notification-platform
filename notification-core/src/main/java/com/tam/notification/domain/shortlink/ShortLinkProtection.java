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
     * false表示一定不存在，true表示可能存在或bloom当前不可用
     *
     * @param shortCode
     * @return
     */
    boolean mightContain(String shortCode);

    void addToBloom(String shortCode);

    /**
     * 当前共享ready、当前片和本机trusted是否可以建立信任
     * @return
     */
    boolean isBloomReady();

    boolean beginBloomRebuild();

    void completeBloomRebuild(Collection<String> shortCodes);
}

