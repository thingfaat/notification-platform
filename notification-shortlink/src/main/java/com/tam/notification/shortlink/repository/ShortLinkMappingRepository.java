package com.tam.notification.shortlink.repository;

import com.tam.notification.shortlink.domain.ShortLinkMapping;

public interface ShortLinkMappingRepository {
    /**
     * 尝试占用短码
     *
     * @param mapping
     * @return
     */
    boolean trySave(ShortLinkMapping mapping);
}
