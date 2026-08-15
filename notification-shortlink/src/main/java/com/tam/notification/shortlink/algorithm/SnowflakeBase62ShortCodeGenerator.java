package com.tam.notification.shortlink.algorithm;


import com.tam.notification.domain.shortlink.ShortCodeGenerator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Snowflake ID + 无损base62实验生成器
 * 位布局：41位时间差+10位nodeId+12位序列号
 * 本实现不取模、不截断，因此输出可能超过当前生产路由的8位限制，它是实验Bean，不是生产默认Bean
 */
@Component("snowflakeBase62ShortCodeGenerator")
public class SnowflakeBase62ShortCodeGenerator implements ShortCodeGenerator {

    // nodeId 位数
    private static final long NODE_ID_BITS = 10L;
    // 序列号位数
    private static final long SEQUENCE_BITS = 12L;

    // nodeId最大值
    private static final long MAX_NODE_ID = (1L << NODE_ID_BITS) - 1L;
    // 序列号最大值
    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1L;
    // 时间差最大值
    private static final long MAX_TIMESTAMP_DELTA = (1L << 41L) - 1L;

    // 节点ID左移12位
    private static final long NODE_ID_SHIFT = SEQUENCE_BITS;
    // 时间差左移22位
    private static final long TIMESTAMP_SHIFT = NODE_ID_BITS + SEQUENCE_BITS;

    /**
     * 自定义 epoch 越新，当前编码越短；但完整生命周期仍可能达到 11 位。
     * 自定义基准时间起点
     */
    private static final long EPOCH_MILLIS = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();


    private final Clock clock;
    private final long nodeId;
    private final long maxBackwardMills;
    private final long sequenceWaitTimeoutNanos;

    // 单个生成器内串行修改 lastTimestamp 和 sequence，避免竞态
    private final ReentrantLock lock = new ReentrantLock();

    private long lastTimestamp = -1l;
    private long sequence = 0L;

    public SnowflakeBase62ShortCodeGenerator(
            @Qualifier("shortCodeClock") Clock clock,
            @Value("${notification.shortlink.snowflake.node-id:1}") long nodeId,
            @Value("${notification.shortlink.snowflake.max-backward-ms:50}") long maxBackwardMillis,
            @Value("${notification.shortlink.snowflake.sequence-wait-timeout-ms:10}") long sequenceWaitTimeoutMillis
    ) {
        if (nodeId < 0 || nodeId > MAX_NODE_ID) {
            throw new IllegalArgumentException("nodeId must be between 0 and " + MAX_NODE_ID);
        }
        if (maxBackwardMillis < 0) {
            throw new IllegalArgumentException("maxBackwardMillis 不能小于 0");
        }
        if (sequenceWaitTimeoutMillis <= 0) {
            throw new IllegalArgumentException("sequenceWaitTimeoutMillis 必须大于 0");
        }

        this.clock = clock;
        this.nodeId = nodeId;
        this.maxBackwardMills = maxBackwardMillis;
        this.sequenceWaitTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(sequenceWaitTimeoutMillis);
    }

    @Override
    public String generate() {
        // base62只做无损编码，不改变 nextId()的唯一性
        return Base62Codec.encode(nextId());
    }

    /**
     * 生成snowflake long id，包级可见是为了让同包测试验证位布局与序列边界
     *
     * @return
     */
    long nextId() {
        lock.lock();

        try {
            long timestamp = clock.millis();
            validateTimestampRange(timestamp);

            // 出现时间回拨
            if (timestamp < lastTimestamp) {
                long backwardMills = lastTimestamp - timestamp;
                // 时间回拨超过阈值，抛出异常
                if (backwardMills > maxBackwardMills) {
                    throw new ClockMovedBackwardsException(backwardMills);
                }

                // 小幅回拨使用逻辑时间，避免sleep后仍未追上
                timestamp = lastTimestamp;
            }

            // 同一个时间戳内，序列号递增
            if (timestamp == lastTimestamp) {
                sequence = (sequence + 1L) & MAX_SEQUENCE;
                if (sequence == 0L) {
                    // 同毫秒4096个序列已经使用完，有限等待下一毫秒
                    timestamp = waitUntilNextMillis(lastTimestamp);
                }
            } else {
                // 新毫秒从序列0开始
                sequence = 0L;
            }

            lastTimestamp = timestamp;
            long timestampDelta = timestamp - EPOCH_MILLIS; // 从起始位置开始的时间戳增量
            return (timestampDelta << TIMESTAMP_SHIFT) // 41位时间戳
                    | (nodeId << NODE_ID_SHIFT) // 10位nodeId
                    | sequence; // 12位序列号
        } finally {
            lock.unlock();
        }
    }

    private void validateTimestampRange(long timestamp) {
        long delta = timestamp - EPOCH_MILLIS;
        if (delta < 0) {
            throw new IllegalStateException("当前时间早于 Snowflake 自定义 epoch");
        }

        if (delta > MAX_TIMESTAMP_DELTA) {
            throw new IllegalStateException("41 位时间戳空间已经耗尽");
        }
    }

    /**
     * 等待物理时钟进入下一毫秒
     * 判断顺序非常重要：
     * - 先读取当前时间；
     * - 如果已经进入下一毫秒，立即成功返回；
     * - 只有时间还没有前进时，才检查是否等待超时
     *
     * @param previousTimestamp 已经耗尽 4096 个序列的毫秒
     * @return 大于 previousTimestamp 的新时间戳
     */
    private long waitUntilNextMillis(long previousTimestamp) {
        long waitStartNanos = System.nanoTime();

        while (true) {
            long timestamp = clock.millis();

            /**
             * 先判断成功条件
             * 即使线程因为GC或调度暂停后才恢复，只要物理时间已经前进，就应该使用新的毫秒，而不是误报等待时间
             * 这里的意思是：只要我能拿到的时间是已经超过上一个被消耗完的时间戳的，就是符合我的要求，返回这个时间戳
             */
            if (timestamp > previousTimestamp) {
                validateTimestampRange(timestamp);

                // 新毫秒的第一个ID从sequence=0开始
                sequence = 0;
                return timestamp;
            }

            // 时间仍未前进时，才检查是否已经允许超过等待时间
            long elapsedNanos = System.nanoTime() - waitStartNanos;
            if (elapsedNanos >= sequenceWaitTimeoutNanos) {
                throw new IllegalStateException("同毫秒序列已耗尽，等待下一毫秒超时");
            }

            // 提示JVM：当前线程正在进行短时间自旋等待
            Thread.onSpinWait();
        }
    }
}
