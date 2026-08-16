package com.tam.notification.shortlink;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 只使用 UTC epochSecond 计算时间片，避免多节点系统时区不一致
 */
public final class BloomSliceWindow {
    private final Clock clock;
    private final long sliceSeconds;
    private final int retainedSliceCount;

    public BloomSliceWindow(
            Clock clock,
            Duration sliceDuration,
            int retainedSliceCount
    ) {
        if (sliceDuration == null || sliceDuration.getSeconds() <= 0) {
            throw new IllegalArgumentException("sliceDuration must be at least one second");
        }
        if (retainedSliceCount <= 0) {
            throw new IllegalArgumentException("retainedSliceCount must be positive");
        }
        this.clock = clock;
        this.sliceSeconds = sliceDuration.getSeconds();
        this.retainedSliceCount = retainedSliceCount;
    }

    public long currentSliceStart() {
        long now = clock.instant().getEpochSecond();
        return Math.floorDiv(now, sliceSeconds) * sliceSeconds;
    }

    /**
     * 返回顺序为当前片最旧保留片
     *
     * @return
     */
    public List<Long> retainedSliceStarts() {
        long current = currentSliceStart();
        List<Long> starts = new ArrayList<>(retainedSliceCount);

        for (var index = 0; index < retainedSliceCount; index++) {
            starts.add(current - index * sliceSeconds);
        }
        return List.copyOf(starts);
    }

    public long oldestRetainedSliceStart() {
        return currentSliceStart() - (retainedSliceCount - 1L) * sliceSeconds;
    }

    /**
     * bitmap比查询窗口多活一个片宽，作为主动清理失效时的兜底
     *
     * @return
     */
    public Duration bitmapTtl() {
        return Duration.ofSeconds(sliceSeconds * (retainedSliceCount + 1L));
    }

    /**
     * ready 只需要覆盖当前片和一次轮换延迟
     *
     * @return
     */
    public Duration readyTtl() {
        return Duration.ofSeconds(sliceSeconds * 2L);
    }
}
