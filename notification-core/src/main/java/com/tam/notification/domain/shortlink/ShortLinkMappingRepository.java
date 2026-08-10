package com.tam.notification.domain.shortlink;

public interface ShortLinkMappingRepository {
    /**
     * 尝试占用短码
     *
     * @param mapping
     * @return
     */
    boolean trySave(ShortLinkMapping mapping);
}
