package com.tam.notification.sharding;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShardRoutingSimulatorTest {

    private static final int DATABASE_COUNT = 16;
    private static final int TABLE_COUNT = 64;
    private static final int KEY_COUNT = 1_000_000;
    private static final long FIRST_KEY = 1_900_000_000_000_000_000L;

    @Test
    void sameHashModuloShouldLeaveMostPhysicalNodesEmpty() {
        var report = ShardRoutingSimulator.simulate(
                FIRST_KEY,
                KEY_COUNT,
                DATABASE_COUNT,
                TABLE_COUNT,
                ShardRoutingSimulator.correlatedModulo(
                        DATABASE_COUNT,
                        TABLE_COUNT
                )
        );

        System.out.println("相关取模：" + report.summary());

        assertAll(
                () -> assertEquals(1_024, report.physicalShardCount()),
                // 16 库 × 64 表理论 1024 个节点，实际上只有 64 个可达。
                () -> assertEquals(64, report.activeShardCount()),
                () -> assertEquals(960, report.emptyShardCount()),
                () -> assertEquals(0, report.min()),
                () -> assertTrue(report.maxToAverage() > 16.0D),
                () -> assertTrue(
                        report.coefficientOfVariation() > 3.8D
                )
        );
    }

    @Test
    void independentBitSegmentsShouldUseAllPhysicalNodes() {
        var report = ShardRoutingSimulator.simulate(
                FIRST_KEY,
                KEY_COUNT,
                DATABASE_COUNT,
                TABLE_COUNT,
                ShardRoutingSimulator.independentBitSegments(
                        DATABASE_COUNT,
                        TABLE_COUNT
                )
        );

        System.out.println("独立位段：" + report.summary());

        assertAll(
                () -> assertEquals(1_024, report.activeShardCount()),
                () -> assertEquals(0, report.emptyShardCount()),
                // 一百万 Key 的随机波动允许存在，但最大节点不应偏离平均值太多。
                () -> assertTrue(report.maxToAverage() < 1.15D),
                () -> assertTrue(
                        report.coefficientOfVariation() < 0.05D
                )
        );
    }

    @Test
    void independentHashesShouldSupportNonPowerOfTwoCounts() {
        int databaseCount = 3;
        int tableCount = 10;

        var report = ShardRoutingSimulator.simulate(
                FIRST_KEY,
                KEY_COUNT,
                databaseCount,
                tableCount,
                ShardRoutingSimulator.independentHashes(
                        databaseCount,
                        tableCount
                )
        );

        System.out.println("独立 seed 哈希：" + report.summary());

        assertAll(
                () -> assertEquals(30, report.activeShardCount()),
                () -> assertEquals(0, report.emptyShardCount()),
                () -> assertTrue(report.maxToAverage() < 1.03D),
                () -> assertTrue(
                        report.coefficientOfVariation() < 0.02D
                )
        );
    }

    @Test
    void bitSegmentRouterShouldRejectNonPowerOfTwoCounts() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> ShardRoutingSimulator.independentBitSegments(3, 10)
        );

        assertTrue(exception.getMessage().contains("2 的幂"));
    }

    @Test
    void floorModBasedRouterShouldNeverReturnNegativeIndex() {
        var router = ShardRoutingSimulator.independentHashes(3, 10);

        var route = router.route(Long.MIN_VALUE);

        assertAll(
                () -> assertTrue(route.databaseIndex() >= 0),
                () -> assertTrue(route.databaseIndex() < 3),
                () -> assertTrue(route.tableIndex() >= 0),
                () -> assertTrue(route.tableIndex() < 10)
        );
    }
}
