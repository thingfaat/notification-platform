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
     *
     * @return
     */
    boolean isBloomReady();

    /**
     * 尝试取得 Bloom 重建资格。
     *
     * @return true 表示当前实例取得了分布式重建锁；
     * false 表示其他实例正在重建
     */
    boolean beginBloomRebuild();

    /**
     * 完成 Bitmap 写入并发布 ready。
     *
     * @return true 表示当前实例仍持有重建锁并成功发布；
     * false 表示锁已失效或发布失败
     */
    boolean completeBloomRebuild(Collection<String> shortCodes);

    /**
     * MySQL 扫描或重建过程异常时，中止本次重建。
     * 只有锁中的 token 仍属于当前实例时，才能释放锁。
     */
    void abortBloomRebuild();
}

