package com.tam.notification.domain.shortlink;

import java.util.Optional;

public interface ShortLinkRepository {

    /**
     * 尝试创建业务短链
     *
     * @param shortLink
     * @return true 表示当前事务赢得唯一键竞争；false 表示已有并发 winner
     */
    boolean trySave(ShortLink shortLink);

    Optional<ShortLink> findById(Long id);

    /**
     * mybatis plus租户拦截器会自动处理租户ID
     * 因此这里只需要显式传入 applicationId、businessType和idempotencyKey
     * @param applicationId
     * @param businessType
     * @param idempotencyKey
     * @return
     */
    Optional<ShortLink> findByIdempotencyKey(
            Long applicationId,
            ShortLinkBusinessType businessType,
            String idempotencyKey
    );
}
