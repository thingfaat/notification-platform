package com.tam.notification.shortlink.service;

import com.tam.notification.common.trace.TraceContext;
import com.tam.notification.domain.shortlink.ShortLinkClickEvent;
import com.tam.notification.shortlink.dto.ResolvedShortLink;
import com.tam.notification.domain.shortlink.ShortLinkClickEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 跳转链接点击记录服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShortLinkClickRecordService {

    private final ShortLinkClickEventPublisher eventPublisher;

    public void record(
            ResolvedShortLink resolved,
            String visitorKey
    ) {
        ShortLinkClickEvent event = new ShortLinkClickEvent(
                UUID.randomUUID().toString().replace("-", ""),
                resolved.tenantId(),
                resolved.shortLinkId(),
                resolved.shortCode(),
                visitorKey,
                TraceContext.getTraceId(),
                LocalDateTime.now()
        );

        try {
            eventPublisher.publish(event);
        } catch (Exception e) {
            // 统计旁路失败不能影响跳转主链路
            log.warn(
                    "publish short-link click event failed, eventId={}, shortCode={}",
                    event.eventId(),
                    event.shortCode(),
                    e
            );
        }
    }
}
