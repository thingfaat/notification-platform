package com.tam.notification.domain.shortlink;

/**
 * 事件发布接口
 */
public interface ShortLinkClickEventPublisher {
    void publish(ShortLinkClickEvent event);
}
