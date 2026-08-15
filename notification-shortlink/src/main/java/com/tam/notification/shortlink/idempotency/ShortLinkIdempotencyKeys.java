package com.tam.notification.shortlink.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 短链业务幂等工厂
 * tenantId、applicationId和businessType已经进入数据库唯一索引
 * 因此这里生成的只是业务类型内部的key
 */
public final class ShortLinkIdempotencyKeys {

    private final static int MAX_MANAGEMENT_REQUEST_ID_LENGTH = 64;

    private ShortLinkIdempotencyKeys() {
    }

    /**
     * 管理端创建直接使用客户端 requestId
     * requestId区分大小写，只去除首位空格，不擅自改变客户端语义
     *
     * @param requestId
     * @return
     */
    public static String management(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId不能为空");
        }

        String normalized = requestId.trim();
        if (normalized.length() > MAX_MANAGEMENT_REQUEST_ID_LENGTH) {
            throw new IllegalArgumentException("requestId长度不能超过64个字符");
        }

        return normalized;
    }

    /**
     * 同一个消息可能包含多个目标链接，因此 messageId不能单独作为幂等键
     * 对URL做 SHA-256只是为了得到固定长度键，真正复用时仍会比较原始URL
     *
     * @param messageId
     * @param targetUrl
     * @return
     */
    public static String messageTracking(Long messageId, String targetUrl) {
        if (messageId == null || messageId <= 0L) {
            throw new IllegalArgumentException("messageId必须大于0");
        }
        if (targetUrl == null || targetUrl.isBlank()) {
            throw new IllegalArgumentException("targetUrl不能为空");
        }
        return messageId + ":" + sha256(targetUrl.trim());
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            // java 运行时必须提供 sha-256,缺失说明运行环境异常
            throw new IllegalStateException("当前JDK不支持sha-256", exception);
        }
    }
}
