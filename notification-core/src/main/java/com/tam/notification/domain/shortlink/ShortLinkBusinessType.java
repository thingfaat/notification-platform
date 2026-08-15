package com.tam.notification.domain.shortlink;

/**
 * 短链的业务用途
 * 不同用途拥有不同的额幂等语义，不能只按originUrl去重
 */
public enum ShortLinkBusinessType {

    // 管理端主动创建，使用客户端requestId幂等
    MANAGEMENT,

    // 通知消息中的追踪链接，使用 message_id+targetUrl摘要幂等
    MESSAGE_TRACKING
}
