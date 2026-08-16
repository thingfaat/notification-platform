package com.tam.notification.shortlink;

/**
 * Redis Key 的唯一构造入口，禁止业务代码手工拼接
 */
public final class ShortLinkRedisKeys {

    private final static String BLOOM_TAG = "bloom:v3";

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
        return String.format("shortlink:{%s}:click:count", requireShortCode(shortCode));
    }

    public static String bloomSlice(long sliceStartEpochSecond) {
        if (sliceStartEpochSecond < 0) {
            throw new IllegalArgumentException("sliceStartEpochSecond must not be negative");
        }
        return "shortlink:{" + BLOOM_TAG + "}:slice:" + sliceStartEpochSecond;
    }

    public static String bloomReady() {
        return "shortlink:{" + BLOOM_TAG + "}:ready";
    }

    public static String bloomSliceRegistry() {
        return "shortlink:{" + BLOOM_TAG + "}:slices";
    }

    private static String requireShortCode(String shortCode) {
        if (shortCode == null || shortCode.isBlank()) {
            throw new IllegalArgumentException("shortCode不能为空");
        }

        /*
         * 大括号会改变 Redis Hash Tag 解析语义。
         * 当前合法短码只有 Base62，本检查用于防止未来调用方绕过校验。
         */
        if (shortCode.indexOf('{') >= 0 || shortCode.indexOf('}') >= 0) {
            throw new IllegalArgumentException("shortCode不能包含大括号");
        }

        return shortCode;
    }
}
