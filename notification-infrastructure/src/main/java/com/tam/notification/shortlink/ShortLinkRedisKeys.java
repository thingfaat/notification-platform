package com.tam.notification.shortlink;

/**
 * 通知短链 redis key的统一入口
 * shortCode当前是全局唯一 8 位base62，因此客户直接作为hash tag。
 * 禁止调用方自己拼接key，避免同一业务对象意外散落到不同槽位
 */
public final class ShortLinkRedisKeys {

    private final static String BLOOM_TAG = "bloom:v2";

    private ShortLinkRedisKeys() {
    }

    public static String redirect(String shortCode) {
        return String.format("shortlink:{%s}:redirect", requireShortCode(shortCode));
    }

    public static String negative(String shortCode) {
        return String.format("shortlink:{%s}:negative", requireShortCode(shortCode));
    }

    /**
     * day18只验证key设计，不改变现有 rocket mq+mysql点击统计链路
     *
     * @param shortCode
     * @return
     */
    public static String clickCount(String shortCode) {
        return String.format("shortlink:{%s}:clickCount", requireShortCode(shortCode));
    }

    public static String bloomBitmap() {
        return String.format("shortlink:{%s}:bitmap", BLOOM_TAG);
    }

    public static String bloomReady() {
        return String.format("shortlink:{%s}:ready", BLOOM_TAG);
    }

    private static String requireShortCode(String shortCode) {
        if (shortCode == null || shortCode.isBlank()) {
            throw new IllegalArgumentException("shortCode不能为空");
        }

        /*
         * 大括号会改变 Redis Hash Tag 解析语义。
         * 当前合法短码只有 Base62，本检查用于防止未来调用方绕过校验。
         */
        if (shortCode.indexOf('{' ) >= 0 || shortCode.indexOf('}' ) >= 0) {
            throw new IllegalArgumentException("shortCode不能包含大括号");
        }

        return shortCode;
    }
}
