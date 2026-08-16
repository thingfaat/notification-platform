package com.tam.notification.sharding;

import java.util.Arrays;
import java.util.Locale;

/**
 * 分片路由纯java模拟器
 * 只放在test目录，不接管生产数据源，day22先用数学和统计证明路由特性，day23在把选定算法接入shardingSphere，避免“配置写完才发现算法有问题”
 */
public final class ShardRoutingSimulator {

    public static final int ONE_MILLION = 1_000_000;

    // 使用两个不同 seed，避免库路由与表路由共享同一组低位
    private static final long DATABASE_SEED = 0x9E3779B97F4A7C15L;
    private static final long TABLE_SEED = 0xC2B2AE3D27D4EB4FL;

    private ShardRoutingSimulator() {
    }

    /**
     * 错误示范：同一个hash分别对应库数和表数取模
     * 当 databaseCount 能整除tableCount时，dbIndex与tableIndex相关，
     * 大量 db/table 组合从数学上永远不可达
     *
     * @param databaseCount
     * @param tableCount
     * @return
     */
    public static Router correlatedModulo(
            int databaseCount,
            int tableCount
    ) {
        validatePositiveCounts(databaseCount, tableCount);

        return key -> {
            long hash = mix64(key);
            return new ShardRoute(
                    floorMod(hash, databaseCount),
                    floorMod(hash, tableCount)
            );
        };
    }

    /**
     * 修正方案一：使用同一个混合hash的不重叠位段。
     * table使用低tableBits位，database使用更高的dbBits位。
     * 该方案要求库数、表数都是2的幂
     *
     * @param databaseCount
     * @param tableCount
     * @return
     */
    public static Router independentBitSegments(
            int databaseCount,
            int tableCount
    ) {
        validatePowerOfTwo(databaseCount, "databaseCount");
        validatePowerOfTwo(tableCount, "tableCount");

        int tableBits = Integer.numberOfTrailingZeros(tableCount);
        long databaseMask = databaseCount - 1L;
        long tableMask = tableCount - 1L;

        return key -> {
            long hash = mix64(key);
            int tableIndex = (int) (hash & tableMask);
            int databaseIndex = (int) ((hash >>> tableBits) & databaseMask);
            return new ShardRoute(databaseIndex, tableIndex);
        };
    }

    /**
     * 修正方案二：库、表使用不同seed计算独立hash
     * 它不要求库数、表数是2的幂；floorMod还能正确处理负数和MIN_VALUE
     *
     * @param databaseCount
     * @param tableCount
     * @return
     */
    public static Router independentHashes(
            int databaseCount,
            int tableCount
    ) {
        validatePositiveCounts(databaseCount, tableCount);

        return key -> {
            long databaseHash = mix64(key ^ DATABASE_SEED);
            long tableHash = mix64(key ^ TABLE_SEED);
            return new ShardRoute(
                    floorMod(databaseHash, databaseCount),
                    floorMod(tableHash, tableCount)
            );
        };
    }

    /**
     * 模拟路由
     * @param firstKey
     * @param keyCount
     * @param databaseCount
     * @param tableCount
     * @param router
     * @return
     */
    public static DistributionReport simulate(
            long firstKey,
            int keyCount,
            int databaseCount,
            int tableCount,
            Router router
    ) {
        validatePositiveCounts(databaseCount, tableCount);
        if (keyCount <= 0) {
            throw new IllegalArgumentException("keyCount 必须大于 0");
        }
        if (router == null) {
            throw new IllegalArgumentException("router 不能为空");
        }

        int physicalShardCount = Math.multiplyExact(
                databaseCount,
                tableCount
        );

        long[] counts = new long[physicalShardCount];

        for (int offset = 0; offset < keyCount; offset++) {
            long key = Math.addExact(firstKey, offset);
            ShardRoute route = router.route(key);
            validateRoute(route, databaseCount, tableCount);

            int flatIndex = route.databaseIndex() * tableCount + route.tableIndex();
            counts[flatIndex]++;
        }

        return buildReport(
                keyCount,
                databaseCount,
                tableCount,
                counts
        );
    }

    /**
     * 构建分布报告
     * @param keyCount
     * @param databaseCount
     * @param tableCount
     * @param counts
     * @return
     */
    private static DistributionReport buildReport(
            int keyCount,
            int databaseCount,
            int tableCount,
            long[] counts
    ) {
        int activeShardCount = 0;
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;

        for (long count : counts) {
            if (count > 0) {
                activeShardCount++;
            }
            min = Math.min(min, count);
            max = Math.max(max, count);
        }

        int physicalShardCount = counts.length;
        double average = (double) keyCount / physicalShardCount;
        double squaredDeviationSum = 0.0D;

        for (long count : counts) {
            double deviation = count - average;
            squaredDeviationSum += deviation * deviation;
        }

        double standardDeviation = Math.sqrt(
                squaredDeviationSum / physicalShardCount
        );
        double coefficientOfVariation = standardDeviation / average;

        return new DistributionReport(
                keyCount,
                databaseCount,
                tableCount,
                physicalShardCount,
                activeShardCount,
                physicalShardCount - activeShardCount,
                min,
                max,
                average,
                max / average,
                coefficientOfVariation,
                Arrays.copyOf(counts, counts.length)
        );
    }

    /**
     * MurmurHash3 的 64 位 finalizer。
     * <p>
     * 它不是加密哈希，只用于把结构化 long ID 的各个位充分混合。
     */
    static long mix64(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        return value ^ (value >>> 33);
    }

    private static int floorMod(long value, int modulus) {
        return (int) Math.floorMod(value, (long) modulus);
    }

    private static void validateRoute(
            ShardRoute route,
            int databaseCount,
            int tableCount
    ) {
        if (route == null) {
            throw new IllegalStateException("路由结果不能为空");
        }
        if (route.databaseIndex() < 0
                || route.databaseIndex() >= databaseCount) {
            throw new IllegalStateException(
                    "databaseIndex 越界：" + route.databaseIndex()
            );
        }
        if (route.tableIndex() < 0
                || route.tableIndex() >= tableCount) {
            throw new IllegalStateException(
                    "tableIndex 越界：" + route.tableIndex()
            );
        }
    }

    private static void validatePositiveCounts(
            int databaseCount,
            int tableCount
    ) {
        if (databaseCount <= 0 || tableCount <= 0) {
            throw new IllegalArgumentException("库数和表数必须大于 0");
        }
    }

    private static void validatePowerOfTwo(int value, String name) {
        if (value <= 0 || (value & (value - 1)) != 0) {
            throw new IllegalArgumentException(
                    name + " 必须是 2 的幂，实际值=" + value
            );
        }
    }

    /**
     * 直接运行 main 可得到三种算法的一百万 Key 摘要。
     */
    public static void main(String[] args) {
        int databaseCount = 16;
        int tableCount = 64;
        long firstKey = 1_900_000_000_000_000_000L;

        print(
                "同 hash 相关取模",
                simulate(
                        firstKey,
                        ONE_MILLION,
                        databaseCount,
                        tableCount,
                        correlatedModulo(databaseCount, tableCount)
                )
        );
        print(
                "独立位段",
                simulate(
                        firstKey,
                        ONE_MILLION,
                        databaseCount,
                        tableCount,
                        independentBitSegments(databaseCount, tableCount)
                )
        );
        print(
                "独立 seed 哈希",
                simulate(
                        firstKey,
                        ONE_MILLION,
                        databaseCount,
                        tableCount,
                        independentHashes(databaseCount, tableCount)
                )
        );
    }

    private static void print(String name, DistributionReport report) {
        System.out.println(name + " -> " + report.summary());
    }

    @FunctionalInterface
    public interface Router {
        ShardRoute route(long key);
    }

    public record ShardRoute(
            int databaseIndex,
            int tableIndex
    ) {

    }

    public record DistributionReport(
            int keyCount,
            int databaseCount,
            int tableCount,
            int physicalShardCount,
            int activeShardCount,
            int emptyShardCount,
            long min,
            long max,
            double average,
            double maxToAverage,
            double coefficientOfVariation,
            long[] counts
    ) {

        public DistributionReport {
            // 防止调用方通过传入数组修改报告内部统计。
            counts = Arrays.copyOf(counts, counts.length);
        }

        @Override
        public long[] counts() {
            return Arrays.copyOf(counts, counts.length);
        }

        public String summary() {
            return String.format(
                    Locale.ROOT,
                    "keys=%d, nodes=%d, active=%d, empty=%d, min=%d, max=%d, avg=%.2f, max/avg=%.4f, CV=%.4f",
                    keyCount,
                    physicalShardCount,
                    activeShardCount,
                    emptyShardCount,
                    min,
                    max,
                    average,
                    maxToAverage,
                    coefficientOfVariation
            );
        }
    }
}
