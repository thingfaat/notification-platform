package com.tam.notification.shortlink.dto;

import com.tam.notification.domain.shortlink.ShortLinkBusinessType;
import com.tam.notification.shortlink.idempotency.ShortLinkIdempotencyKeys;

import java.time.LocalDateTime;

/**
 * 创建短链命令
 * HTTP管理端和内部消息追踪使用不同工厂方法，避免调用方拼错幂等键
 *
 * @param applicationId
 * @param originalUrl
 * @param expireAt
 */
public record CreateShortLinkCommand(
        Long applicationId,
        String originalUrl,
        LocalDateTime expireAt,
        ShortLinkBusinessType businessType,
        String idempotencyKey
) {

    /**
     * 管理端创建短链命令
     *
     * @param applicationId
     * @param requestId
     * @param originalUrl
     * @param expireAt
     * @return
     */
    public static CreateShortLinkCommand management(
            Long applicationId,
            String requestId,
            String originalUrl,
            LocalDateTime expireAt
    ) {
        return new CreateShortLinkCommand(
                applicationId,
                originalUrl,
                expireAt,
                ShortLinkBusinessType.MANAGEMENT,
                ShortLinkIdempotencyKeys.management(requestId)
        );
    }

    /**
     * 创建通知消息中的追踪链接短链命令
     *
     * @param applicationId
     * @param messageId
     * @param targetUrl
     * @param expireAt
     * @return
     */
    public static CreateShortLinkCommand messageTracking(
            Long applicationId,
            Long messageId,
            String targetUrl,
            LocalDateTime expireAt
    ) {
        return new CreateShortLinkCommand(
                applicationId,
                targetUrl,
                expireAt,
                ShortLinkBusinessType.MESSAGE_TRACKING,
                ShortLinkIdempotencyKeys.messageTracking(messageId, targetUrl)
        );
    }
}
