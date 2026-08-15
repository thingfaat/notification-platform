package com.tam.notification.shortlink.algorithm;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

public class SnowflakeBase62ShortCodeGeneratorTest {

    private static final long BASE_MILLIS = Instant.parse("2026-08-15T00:00:00Z").toEpochMilli();

    /**
     * 测试生成 1_000_000 个短码，确保没有重复。
     */
    @Test
    void shouldGenerateOneMillionUniqueCodesSequentially() {
        SnowflakeBase62ShortCodeGenerator generator = generator(Clock.systemUTC());

        int sampleSize = 1_000_000;
        Set<String> codes = new HashSet<>(1_400_000);

        for (int index = 0; index < sampleSize; index++) {
            String code = generator.generate();

            if (!codes.add(code)) {
                throw new AssertionError("发现重复短码: " + code);
            }
        }

        assertEquals(sampleSize, codes.size());
    }

    /**
     * 测试生成 32 个线程，每个线程生成 10_000 个短码，确保没有重复。
     * @throws Exception
     */
    @Test
    void shouldGenerateUniqueCodesWithThirtyTwoThreads() throws Exception {
        SnowflakeBase62ShortCodeGenerator generator = generator(Clock.systemUTC());

        int threadCount = 32;
        int codesPerThread = 10_000;
        int expectedTotal = threadCount * codesPerThread;

        Set<String> codes = ConcurrentHashMap.newKeySet();
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);

        try {
            Future<?>[] futures = new Future<?>[threadCount];

            for (int thread = 0; thread < threadCount; thread++) {
                futures[thread] = executor.submit(() -> {
                    startGate.await();

                    for (int index = 0; index < codesPerThread; index++) {
                        String code = generator.generate();

                        if (!codes.add(code)) {
                            throw new AssertionError(
                                    "并发生成重复短码: " + code
                            );
                        }
                    }

                    return null;
                });
            }

            startGate.countDown();

            for (Future<?> future : futures) {
                // get 会把工作线程中的断言失败传播到主测试线程。
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(expectedTotal, codes.size());
    }

    /**
     * 测试生成 5ms 和 50ms 回拨短码，确保没有重复。
     */
    @Test
    void shouldKeepUniqueForFiveAndFiftyMillisecondRollback() {
        MutableClock clock = new MutableClock(BASE_MILLIS);
        SnowflakeBase62ShortCodeGenerator generator = generator(clock);

        String beforeRollback = generator.generate();

        clock.setMillis(BASE_MILLIS - 5L);
        String fiveMillisRollback = generator.generate();

        clock.setMillis(BASE_MILLIS - 50L);
        String fiftyMillisRollback = generator.generate();

        assertNotEquals(beforeRollback, fiveMillisRollback);
        assertNotEquals(fiveMillisRollback, fiftyMillisRollback);
        assertNotEquals(beforeRollback, fiftyMillisRollback);
    }

    /**
     * 测试回拨超过 50ms 的情况。
     */
    @Test
    void shouldRejectRollbackGreaterThanFiftyMilliseconds() {
        MutableClock clock = new MutableClock(BASE_MILLIS);
        SnowflakeBase62ShortCodeGenerator generator = generator(clock);

        generator.generate();
        clock.setMillis(BASE_MILLIS - 51L);

        ClockMovedBackwardsException exception = assertThrows(
                ClockMovedBackwardsException.class,
                generator::generate
        );

        assertEquals(51L, exception.getBackwardMillis());
    }

    /**
     * 测试序列耗尽
     */
    @Test
    void shouldMoveToNextMillisAfterSequenceExhaustion() {
        /*
         * 前 4097 次读取都返回同一毫秒：
         * - 第 1 个 ID 使用 sequence=0；
         * - 随后使用 sequence=1...4095；
         * - 第 4097 个 ID 触发等待；
         * - 等待中的下一次读取返回 base+1ms。
         */
        Clock clock = new FixedThenAdvanceClock(
                BASE_MILLIS,
                4097L
        );

        SnowflakeBase62ShortCodeGenerator generator = generator(clock);
        Set<String> codes = new HashSet<>();

        for (int index = 0; index < 4097; index++) {
            String code = generator.generate();

            if (!codes.add(code)) {
                throw new AssertionError(
                        "序列耗尽实验发现重复短码: " + code
                );
            }
        }

        assertEquals(4097, codes.size());
    }

    /**
     * 生成器
     * @param clock
     * @return
     */
    private SnowflakeBase62ShortCodeGenerator generator(Clock clock) {
        return new SnowflakeBase62ShortCodeGenerator(
                clock,
                1L,
                50L,
                10L
        );
    }

    /**
     * 可手动前进或回拨的测试时钟。
     */
    private static final class MutableClock extends Clock {

        private final AtomicLong currentMillis;

        private MutableClock(long initialMillis) {
            this.currentMillis = new AtomicLong(initialMillis);
        }

        void setMillis(long millis) {
            currentMillis.set(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(currentMillis.get());
        }

        @Override
        public long millis() {
            return currentMillis.get();
        }
    }

    /**
     * 前 sameMillisReadCount 次读取固定时间，之后自动前进 1ms。
     * 用它可确定性验证同毫秒 4096 序列耗尽，不依赖 sleep。
     */
    private static final class FixedThenAdvanceClock extends Clock {

        private final long baseMillis;
        private final long sameMillisReadCount;
        private final AtomicLong readCount = new AtomicLong();

        private FixedThenAdvanceClock(
                long baseMillis,
                long sameMillisReadCount
        ) {
            this.baseMillis = baseMillis;
            this.sameMillisReadCount = sameMillisReadCount;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis());
        }

        @Override
        public long millis() {
            long currentRead = readCount.incrementAndGet();
            return currentRead <= sameMillisReadCount
                    ? baseMillis
                    : baseMillis + 1L;
        }
    }
}
