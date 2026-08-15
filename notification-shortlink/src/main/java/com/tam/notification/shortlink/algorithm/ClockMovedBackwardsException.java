package com.tam.notification.shortlink.algorithm;

import lombok.Getter;

/**
 * 时钟回拨超过容忍阈值时抛出
 * 严重回拨必须显式失败，不能静默生成可能重复的ID
 */
public class ClockMovedBackwardsException extends IllegalStateException {

    // 时钟回拨毫秒数
    @Getter
    private final long backwardMillis;

    public ClockMovedBackwardsException(long backwardMillis) {
        super("xitong0时钟严重回拨：" + backwardMillis + "毫秒");
        this.backwardMillis = backwardMillis;
    }
}
