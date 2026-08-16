# Day 19：时间分片 Bloom Filter——完整教学版

> 本文只生成教学文档，不修改 `notification-platform` 或 `short_link` 项目源码。
>
> 2026-08-16 验收修订：原文遗漏多实例交错重建竞态；最终代码已改为带 token/TTL 的同槽分布式重建锁，并同步更新完整代码、并发实验和生命周期记录。
>
> 真实代码基线：
>
> - 通知平台：`/Users/hingfaattam/workspace/learn_workspace/notification-platform`
> - 通知平台基线提交：`864d71896a9be6ef3e8287d90f9cf19db2fdd6b1`
> - 知识星球短链：`/Users/hingfaattam/workspace/learn_workspace/short_link`
> - 学习计划：原 30 天计划在 Day16—Day18 文档中保留的进度说明，以及《多租户统一通知平台-补充学习计划》
>
> 文中的代码是你完成 Day19 时需要手动加入通知平台的完整增量，注释解释了设计原因。本文不会让你修改知识星球项目。

补充学习计划对 Day19 的要求是：根据真实样本量计算 Bloom 参数；只实现共享 Redis 时间分片；完成当前片、历史片、轮换、清理和启动预热；保留现有 `ready/trusted` 与 fail-open；采集理论与实际误判率；不使用 Redis Stream。

今天的最终设计先写在前面：

```text
数据位置：只使用 Redis Cluster 中的共享 Bitmap，不增加本地 Bloom
时间基准：UTC Clock，可在测试中推进，不修改操作系统时间
默认片宽：6 小时
默认保留：4 片，即当前片 + 3 个历史片

每次进入新片：
Lua 抢占 token 重建锁并清除 ready → 查询 MySQL 中仍有效的短码
→ 每批校验 token 后写当前片 → 校验 token 后原子发布 ready 并释放锁
→ 清理保留窗口外的旧片

新短链创建：事务提交后写入当前片

查询：
ready 不可信 / 重建中 / Redis 故障 → 返回“可能存在”，放行 MySQL
任意保留片全部位为 1 → 可能存在，查询 MySQL
所有保留片均不命中 → 一定不存在，可以拦截

明确不做：
本地 Bloom、Redis Stream、Redisson RBloomFilter、2.16 亿容量/片、永久 Bitmap
```

---

# 一、原理

## 1.1 Bloom Filter 的位图模型

Bloom Filter 使用长度为 `m` 的位图和 `k` 个哈希位置。写入元素时把 `k` 个位置设为 1；查询时只要有一个位置为 0，就能确定元素不存在。

```text
写入 codeA：hash1 → 18，hash2 → 93，hash3 → 201
查询 codeA：18、93、201 全是 1 → 可能存在
查询 codeB：其中一个位置是 0 → 一定不存在
```

它有两条关键语义：

- Bloom 说“不存在”时，结果必须可靠；
- Bloom 说“可能存在”时，允许误判，最终仍要查 MySQL。

短链跳转链路只能使用第一条做拦截，不能把 Bloom 当事实来源。

## 1.2 为什么普通 Bloom 不能直接删除

多个元素可能共享同一个 bit：

```text
codeA → bit 18、93、201
codeB → bit 18、77、305
```

如果 codeA 过期后把 bit 18 清零，codeB 会被错误判断为不存在，形成 false negative。普通 Bitmap Bloom 因此只支持置 1，不支持安全地按元素清零。

Counting Bloom 可以为每个位置保存计数器，但内存明显增加，还要处理计数溢出和并发更新。Day19 不引入它。

## 1.3 永久 Bitmap 为什么会恶化

现有通知平台只有一个全局 Bitmap：

```text
shortlink:{bloom:v2}:bitmap
```

短链过期后，对应 bit 永远保留。随着累计写入数量 `n` 增长，误判率近似为：

```text
p = (1 - e^(-kn/m))^k
```

`m` 和 `k` 固定、`n` 持续增长时，误判率会不断升高。Bloom 不会误伤合法短链，但会越来越少拦截无效请求，最终失去防穿透价值。

## 1.4 时间分片如何让旧数据退出

把一个永久 Bitmap 改成多个时间片：

```text
12:00—18:00 → slice:1723809600
18:00—24:00 → slice:1723831200
下一天 00:00—06:00 → slice:1723852800
```

查询时检查当前片和保留窗口内的历史片。窗口外的整个 Bitmap 可以安全删除，因为删除的是完整过滤器，不会清除其他片中元素的 bit。

本课程在每次轮换时把“当前仍有效的全部短码”重建到新片，而不是只把新创建短码放入新片。这样长期有效短链会被复制到下一片，不会因为创建时间超过保留窗口而形成 false negative。

## 1.5 为什么不能照搬知识星球实现

知识星球真实代码位于：

```text
short_link/src/main/java/cn/net/susan/shortlink/service/
RedisTimeBasedBloomFilterService.java

short_link/src/main/java/cn/net/susan/shortlink/filter/
TimeSliceBloomFilter.java
```

值得学习的是“按时间切片、查询多个活跃片、删除过期片”的思想，但不能直接复制：

1. 每片固定 `216,000,000` 个元素、Redis 保留 32 片。按 1% 误判率估算，每片约 247 MiB，32 片接近 8 GiB；通知平台没有数据证明需要该容量。
2. 它同时维护本地 Bloom、Redis Bloom 和 Redis Stream，同一问题出现三套状态；补充计划明确排除本地 Bloom 与 Redis Stream。
3. Redis 查询异常后继续检查其他片，所有片都异常时最终返回 `false`，可能把合法短链误判为不存在；通知平台必须 fail-open。
4. 它只按创建时间写当前片。一个有效期超过 8 天的短链会随着旧片删除而消失，除非另有完整重建。
5. 使用 `LocalDateTime.now()`，不方便稳定测试轮换边界，多节点时还依赖系统时区一致。
6. 没有现有通知平台已经具备的 `ready/trusted` 状态，空片或半成品片可能被误信。

所以 Day19 学习它的问题意识，但重新实现符合通知平台边界的小规模版本。

## 1.6 参数如何计算

给定预期元素数 `n` 和单片误判率 `p_slice`：

```text
m = ceil(-n × ln(p_slice) / (ln 2)^2)
k = round((m / n) × ln 2)
```

如果查询 `r` 个相互独立的片，总体误判率近似为：

```text
p_total = 1 - (1 - p_slice)^r
```

因此配置的是总体目标 `p_total` 时，先反推单片目标：

```text
p_slice = 1 - (1 - p_total)^(1/r)
```

默认实验值：

```text
n = 100,000 个仍有效短码
r = 4 个保留片
p_total = 1%

p_slice ≈ 0.250943%
m = 1,246,262 bits ≈ 152.13 KiB/片
k = 9
4 片 Bitmap 约 608.5 KiB，不含 Redis Key 等少量开销
```

这个数字可以在本机真实验证，不冒充生产容量结论。

## 1.7 ready、trusted 与 fail-open

三种状态要分开：

| 状态 | 含义 |
|---|---|
| Bitmap 存在 | Redis 中有一段位图，不代表内容完整 |
| ready=currentSlice | 当前片完整重建成功，可以参与否定判断 |
| 本机 trusted=true | 本机已经确认共享 ready 与当前片一致 |
| 本机 dirty=true | 本机观察到 Bloom 读写故障；旧 ready 不能重新建立信任 |

重建顺序必须是：

```text
本机 trusted=false
→ Lua 原子抢占带 UUID token 和 TTL 的分布式重建锁
→ 抢锁成功后，在同一个 Lua 中删除共享 ready 和当前片
→ 从 MySQL 读取仍有效短码
→ 每个批次校验 token 后写入当前片
→ 发布 Lua 再次校验 token
→ 设置 TTL、片注册表并最后写 ready=currentSlice
→ 原子释放重建锁
→ 本机 trusted=true
```

任何步骤失败，都不能发布 ready。Bloom 读写异常还会把本机标成 `dirty`；即使 Redis 恢复后还能读到旧 ready，本机也必须继续 fail-open 并由调度器触发完整重建。只有完整重建成功才能清除 dirty。`mightContain` 在 dirty、ready 缺失、片不存在、重建中或 Redis 异常时统一返回 `true`，让请求继续查询 MySQL。

---

# 二、现有数据流

## 2.1 现有创建链路

```text
ShortLinkService.create
→ MySQL 保存 short_link
→ MySQL 占用 short_link_mapping.short_code
→ 发布 ShortLinkCreatedEvent
→ 事务提交
→ ShortLinkCacheConsistencyListener(AFTER_COMMIT)
→ RedisShortLinkProtection.addToBloom
→ shortlink:{bloom:v2}:bitmap SETBIT
```

事务回滚不会污染 Bloom，这条边界继续复用。

## 2.2 现有跳转链路

```text
ShortLinkRedirectService.resolve
→ 本地 Caffeine / Redis 正缓存
→ Redis 负缓存
→ ShortLinkProtection.mightContain
   ├─ false：缓存 NOT_FOUND，结束
   └─ true：跨租户查询 short_link_mapping
             → 带 TenantContext 查询 short_link
             → 校验状态和 expireAt
             → 写正缓存
```

Bloom 只减少非法短码访问 MySQL 的次数，不参与最终存在性判断。

## 2.3 现有启动重建

```text
ShortLinkBloomInitializer.run
→ beginBloomRebuild
   → trusted=false
   → 删除 ready 和整个 v2 Bitmap
→ findAllShortCodesAcrossTenants
→ completeBloomRebuild
   → 逐条写 Bitmap
   → ready=1
   → trusted=true
```

现有问题：每次启动都重建永久 Bitmap；SQL 会把已过期映射重新加入；逐短码一次 Lua 往返；没有轮换和旧数据退出。

---

# 三、本次需要改动的数据流

## 3.1 新创建链路

```text
事务提交后的 ShortLinkCreatedEvent
→ 计算 UTC 当前片起始 epochSecond
→ shortlink:{bloom:v3}:slice:<epochSecond>
→ 一次 Lua 设置该短码的 k 个 bit
→ 刷新当前片 TTL

写失败：
本机 dirty=true、trusted=false → 尽力删除共享 ready
→ 后续请求持续 fail-open → 定时任务完整重建成功后清除 dirty
```

监听器和事件不需要增加 Redis Stream，也不需要本地广播。

## 3.2 新查询链路

```text
mightContain(shortCode)
→ 本机 trusted=false 时，尝试从共享 ready 恢复可信状态
→ Lua 原子检查：
   1. ready 是否等于 UTC 当前片
   2. 当前片 Bitmap 是否存在
   3. 当前片和历史片是否任意一片的 k 个 bit 全为 1

ready 不匹配 / 当前片不存在 / Redis 异常 → true，放行 MySQL
任意片命中 → true，查询 MySQL
全部片不命中 → false，缓存 NOT_FOUND
```

Day19 继续复用 Day18 学到的 Hash Tag 规则，让 ready、注册表和所有 v3 Bitmap 都使用 `{bloom:v3}`，因此 Lua 的所有 `KEYS` 落在同一 Redis Cluster slot。

## 3.3 启动预热与轮换

```text
应用启动或每分钟检查
→ ready 已是当前片：直接使用，不重复扫描 MySQL
→ ready 缺失或仍是旧片：
   trusted=false
   → 尝试取得同槽分布式重建锁
   → 抢锁成功后原子清除 ready 和当前片
   → SQL 只查询 status=ACTIVE 且 expire_at>now 的短码
   → 每 500 个短码校验锁 token 后写入
   → 校验 token 后原子发布 ready=currentSlice 并释放锁
   → 清理保留窗口外的片
```

> 2026-08-16 验收修订：初版把“SETBIT 幂等”误认为整个“DEL → 分批 SETBIT → ready”流程可以安全并发。事实上，实例 B 的 DEL 可能清除实例 A 已写入的批次，而 A 随后仍发布 ready，形成残缺 Bitmap。最终实现必须使用带随机 token 和 TTL 的分布式重建锁，并在每批写入和发布 ready 时校验所有权。

多个实例同时触发时，只有锁持有者扫描数据库和删除当前片；其他实例直接退出并等待下一次检查。锁过期的旧实例无法继续写入或发布 ready，因此合法短链不会因为交错重建而被判定为不存在。

## 3.4 旧片清理

```text
ZSET shortlink:{bloom:v3}:slices
member = sliceStartEpochSecond
score  = sliceStartEpochSecond

oldestRetained = current - (retainedCount - 1) × sliceSeconds
score < oldestRetained 的 member → 删除对应 Bitmap → 从 ZSET 移除
```

每个 Bitmap 还设置 `retainedCount + 1` 个片宽的 TTL。ZSET 是主动清理路径，TTL 是进程长期停机或清理失败时的兜底。

## 3.5 多实例和 v2 → v3 滚动升级边界

本地课程可以停止旧 Server、一次性发布 v3，再由启动预热建立当前片。但生产多实例不能直接让 v2 与 v3 代码长期混跑：旧实例创建的短码只写 v2，新实例若已经信任 v3，就可能暂时看不到该短码。

零停机升级应采用三阶段兼容发布：

```text
阶段 A：所有实例先升级为“读 v2、同时写 v2/v3”
阶段 B：完整重建 v3 并验证后，所有实例切为“读 v3、继续双写”
阶段 C：观察窗口结束后停止 v2 写入，人工删除 v2 Key
```

如果系统已经有可靠 Outbox，也可以用耐久事件补偿 v3 写入；不能把当前进程内的 `ShortLinkCreatedEvent` 当成跨实例广播。Day19 不为此引入 Redis Stream。

---

# 四、文件位置（复用 / 新增 / 修改）

| 类型 | 文件 | 作用 |
|---|---|---|
| 复用 | `ShortLinkRedirectService.java` | 跳转顺序和 fail-open 后的 MySQL 回源不变 |
| 复用 | `ShortLinkCacheConsistencyListener.java` | AFTER_COMMIT 增量写当前片 |
| 复用 | `RedisClusterSlot.java` | 验证所有 Bloom v3 Key 同槽 |
| 复用 | `deploy/redis-cluster/*` | 真实 Redis Cluster 实验环境 |
| 新增 | `BloomFilterParameters.java` | 根据样本量和总体误判率计算 m、k |
| 新增 | `BloomSliceWindow.java` | 使用可注入 Clock 计算当前片、历史片和 TTL |
| 新增 | `BloomClockConfig.java` | 提供 UTC `bloomClock` |
| 修改 | `ShortLinkRedisKeys.java` | 增加 v3 slice、ready、registry Key |
| 修改 | `ShortLinkProtection.java` | 增加共享 ready 检查能力 |
| 修改 | `ShortLinkMappingRepository.java` | 只加载仍有效短码，过期判断使用数据库当前时间 |
| 修改 | `ShortLinkMappingMapper.java` | 跨租户 join 查询 ACTIVE、未过期短码 |
| 修改 | `ShortLinkMappingRepositoryImpl.java` | 适配新查询 |
| 修改 | `RedisShortLinkProtection.java` | 时间分片查询、批量重建、轮换清理和 fail-open |
| 修改 | `ShortLinkBloomInitializer.java` | 启动预热 + 定时检查轮换 |
| 修改 | `application.yml` | 样本量、总体误判率、片宽和保留片数 |
| 修改 | `ShortLinkRedisKeysTest.java` | 验证所有 v3 Key 同槽及精确格式 |
| 新增 | `BloomFilterParametersTest.java` | 验证公式 |
| 新增 | `BloomSliceWindowTest.java` | 验证 UTC 边界和窗口推进 |
| 新增 | `RedisShortLinkProtectionFailOpenTest.java` | 验证 Redis 故障不误伤 |
| 新增 | `ShortLinkBloomInitializerTest.java` | 验证启动预热编排 |
| 新增 | `RedisTimeSlicedBloomFilterIntegrationTest.java` | 真实 Cluster 轮换、历史片、清理和误判率 |
| 新增 | `docs/bloom-lifecycle.md` | 记录参数、生命周期、降级和实验数据 |

---

# 五、基于现有代码的完整增量代码

## 5.1 新增 BloomFilterParameters.java

位置：

```text
notification-infrastructure/src/main/java/com/tam/notification/shortlink/BloomFilterParameters.java
```

```java
package com.tam.notification.shortlink;

/**
 * Bloom 参数计算结果。
 *
 * @param expectedInsertions              每个完整快照片预计容纳的有效短码数
 * @param overallFalsePositiveProbability 查询全部保留片后的总体误判率目标
 * @param perSliceFalsePositiveProbability 单片误判率目标
 * @param bitSize                         单片位数 m
 * @param hashFunctions                   哈希函数数量 k
 */
public record BloomFilterParameters(
        long expectedInsertions,
        double overallFalsePositiveProbability,
        double perSliceFalsePositiveProbability,
        long bitSize,
        int hashFunctions
) {
    // Redis String 最大 512 MiB，即最多 2^32 个 bit。
    private static final long MAX_REDIS_BITMAP_BITS = 1L << 32;

    public static BloomFilterParameters calculate(
            long expectedInsertions,
            double overallFalsePositiveProbability,
            int retainedSliceCount
    ) {
        if (expectedInsertions <= 0) {
            throw new IllegalArgumentException("expectedInsertions must be positive");
        }
        if (!(overallFalsePositiveProbability > 0.0
                && overallFalsePositiveProbability < 1.0)) {
            throw new IllegalArgumentException(
                    "overallFalsePositiveProbability must be between 0 and 1"
            );
        }
        if (retainedSliceCount <= 0) {
            throw new IllegalArgumentException("retainedSliceCount must be positive");
        }

        // 查询 r 个片时：p_total = 1 - (1 - p_slice)^r。
        double perSliceProbability = 1.0 - Math.pow(
                1.0 - overallFalsePositiveProbability,
                1.0 / retainedSliceCount
        );

        double ln2 = Math.log(2.0);
        long bitSize = (long) Math.ceil(
                -expectedInsertions * Math.log(perSliceProbability)
                        / (ln2 * ln2)
        );
        int hashFunctions = Math.max(
                1,
                (int) Math.round(
                        bitSize / (double) expectedInsertions * ln2
                )
        );

        if (bitSize > MAX_REDIS_BITMAP_BITS) {
            throw new IllegalArgumentException(
                    "calculated bitmap exceeds Redis 512 MiB string limit: " + bitSize
            );
        }

        return new BloomFilterParameters(
                expectedInsertions,
                overallFalsePositiveProbability,
                perSliceProbability,
                bitSize,
                hashFunctions
        );
    }
}
```

## 5.2 新增 BloomSliceWindow.java

位置：

```text
notification-infrastructure/src/main/java/com/tam/notification/shortlink/BloomSliceWindow.java
```

```java
package com.tam.notification.shortlink;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 只使用 UTC epochSecond 计算时间片，避免多节点系统时区不一致。
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

    /** 返回顺序为当前片到最旧保留片。 */
    public List<Long> retainedSliceStarts() {
        long current = currentSliceStart();
        List<Long> starts = new ArrayList<>(retainedSliceCount);
        for (int index = 0; index < retainedSliceCount; index++) {
            starts.add(current - index * sliceSeconds);
        }
        return List.copyOf(starts);
    }

    public long oldestRetainedSliceStart() {
        return currentSliceStart() - (retainedSliceCount - 1L) * sliceSeconds;
    }

    /** Bitmap 比查询窗口多活一个片宽，作为主动清理失败时的兜底。 */
    public Duration bitmapTtl() {
        return Duration.ofSeconds(sliceSeconds * (retainedSliceCount + 1L));
    }

    /** ready 只需要覆盖当前片和一次轮换延迟。 */
    public Duration readyTtl() {
        return Duration.ofSeconds(sliceSeconds * 2L);
    }
}
```

## 5.3 新增 BloomClockConfig.java

位置：

```text
notification-infrastructure/src/main/java/com/tam/notification/config/BloomClockConfig.java
```

```java
package com.tam.notification.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class BloomClockConfig {

    /** 生产统一使用 UTC；测试可以直接向对象注入 MutableClock。 */
    @Bean("bloomClock")
    public Clock bloomClock() {
        return Clock.systemUTC();
    }
}
```

## 5.4 修改 ShortLinkRedisKeys.java

最终代码增加了与所有 Bloom Key 同槽的重建锁 Key；禁止业务代码手工拼接。

```java
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

    /**
     * Bloom 完整重建的分布式锁。
     * <p>
     * 所有 Bloom Key 都使用 {bloom:v3} Hash Tag，
     * 保证后续多 Key Lua 可以在 Redis Cluster 中执行。
     */
    public static String bloomRebuildLock() {
        return "shortlink:{" + BLOOM_TAG + "}:rebuild:lock";
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
```

## 5.5 修改 ShortLinkProtection.java

重建完成需要返回发布结果；扫描异常还需要显式中止并比较锁 token。

```java
package com.tam.notification.domain.shortlink;

import java.util.Collection;
import java.util.Optional;

/**
 * 防穿透抽象
 */
public interface ShortLinkProtection {

    Optional<ShortLinkNegativeReason> getNegative(String shortCode);

    void cacheNegative(
            String shortCode,
            ShortLinkNegativeReason reason
    );

    void evictNegative(String shortCode);

    /**
     * false表示一定不存在，true表示可能存在或bloom当前不可用
     *
     * @param shortCode
     * @return
     */
    boolean mightContain(String shortCode);

    void addToBloom(String shortCode);

    /**
     * 当前共享ready、当前片和本机trusted是否可以建立信任
     *
     * @return
     */
    boolean isBloomReady();

    /**
     * 尝试取得 Bloom 重建资格。
     *
     * @return true 表示当前实例取得了分布式重建锁；
     * false 表示其他实例正在重建
     */
    boolean beginBloomRebuild();

    /**
     * 完成 Bitmap 写入并发布 ready。
     *
     * @return true 表示当前实例仍持有重建锁并成功发布；
     * false 表示锁已失效或发布失败
     */
    boolean completeBloomRebuild(Collection<String> shortCodes);

    /**
     * MySQL 扫描或重建过程异常时，中止本次重建。
     * 只有锁中的 token 仍属于当前实例时，才能释放锁。
     */
    void abortBloomRebuild();
}
```

## 5.6 修改有效短码查询

`ShortLinkMappingRepository.java` 将原来的全量方法替换为：

```java
/** 仅用于 Bloom 完整快照，普通租户业务禁止调用。 */
List<String> findAllActiveShortCodesAcrossTenants();
```

`ShortLinkMappingMapper.java` 将原来的 `selectAllShortCodesAcrossTenants` 替换为：

```java
@InterceptorIgnore(tenantLine = "1")
@Select("""
        select mapping.short_code
        from short_link_mapping mapping
        inner join short_link link
            on link.id = mapping.short_link_id
           and link.tenant_id = mapping.tenant_id
        where link.status = 'ACTIVE'
          and link.expire_at > current_timestamp(3)
        order by mapping.id
        """)
List<String> selectAllActiveShortCodesAcrossTenants();
```

这里不把 UTC `Clock` 转成 `LocalDateTime` 传给 SQL。现有表使用无时区的 MySQL `DATETIME(3)`，创建和跳转逻辑也使用应用本地 `LocalDateTime`；由数据库连接自己的当前时间比较，可以避免把 UTC 墙上时间误当成本地墙上时间。UTC Clock 只负责计算 Redis 切片编号。

`ShortLinkMappingRepositoryImpl.java` 将对应实现替换为：

```java
@Override
public List<String> findAllActiveShortCodesAcrossTenants() {
    return mappingMapper.selectAllActiveShortCodesAcrossTenants();
}
```

## 5.7 完整修改 RedisShortLinkProtection.java

以下为 2026-08-16 多实例并发验收修订后的完整实现：分布式锁使用随机 token 和 TTL，批次写入、发布与中止都校验所有权。

```java
package com.tam.notification.shortlink;

import com.tam.notification.domain.shortlink.ShortLinkNegativeReason;
import com.tam.notification.domain.shortlink.ShortLinkProtection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * redis布隆过滤器与负缓存实现
 */
@Slf4j
@Service
public class RedisShortLinkProtection implements ShortLinkProtection {

    private static final int REBUILD_BATCH_CODES = 500;
    // 布隆过滤器就绪key
    private static final String BLOOM_READY_KEY = ShortLinkRedisKeys.bloomReady();
    // 布隆过滤器key
    private static final String BLOOM_REGISTRY_KEY = ShortLinkRedisKeys.bloomSliceRegistry();

    /**
     * 小规模课程版本给重建锁 5 分钟租期。
     * <p>
     * 如果重建超过 5 分钟，后续批次和 ready 发布会因为 token 校验失败而终止，
     * 不会把不完整 Bitmap 发布出去。
     */
    private static final Duration REBUILD_LOCK_TTL = Duration.ofMinutes(5);

    private static final String BLOOM_REBUILD_LOCK_KEY =
            ShortLinkRedisKeys.bloomRebuildLock();

    /**
     * 保存本机当前重建的 token 和目标时间片。
     * <p>
     * token 用来证明“这个锁仍然属于我”；
     * sliceStart 防止一次重建跨越时间片后错误发布。
     */
    private final AtomicReference<RebuildContext> activeRebuild = new AtomicReference<>();

    private record RebuildContext(
            String token,
            long sliceStart,
            String bitmapKey
    ) {
    }

    /**
     * 布隆过滤器检查脚本
     * KEYS[1] 是ready，KEYS[2]是当前片，其余是历史片
     * ARGV[1] 是预期当前片，ARGV[2...] 是该短码的bit offset
     * 返回2表示状态不可信，java侧必须fail-open
     */
    private static final DefaultRedisScript<Long> CHECK_SLICES_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then
                return 2
            end

            if redis.call('EXISTS', KEYS[2]) == 0 then
                return 2
            end

            for keyIndex = 2, #KEYS do
                local allSet = 1
                for argIndex = 2, #ARGV do
                    if redis.call(
                        'GETBIT',
                        KEYS[keyIndex],
                        tonumber(ARGV[argIndex])
                    ) == 0 then
                        allSet = 0
                        break
                    end
                end
                if allSet == 1 then
                    return 1
                end
            end

            return 0
            """,
            Long.class
    );

    private static final DefaultRedisScript<Long> SET_BITS_SCRIPT = new DefaultRedisScript<>("""
            for index = 1, #ARGV do
                redis.call('SETBIT', KEYS[1], tonumber(ARGV[index]), 1)
            end
            return #ARGV
            """,
            Long.class
    );

    /**
     * 原子抢锁并进入重建状态
     * KEYS[1] = rebuild lock
     * KEYS[2] = ready
     * KEYS[3] = 当前 Bitmap
     * ARGV[1] = UUID token
     * ARGV[2] = lock TTL 毫秒
     */
    private static final DefaultRedisScript<Long> BEGIN_REBUILD_SCRIPT = new DefaultRedisScript<>("""
            local acquired = redis.call(
                'SET',
                KEYS[1],
                ARGV[1],
                'NX',
                'PX',
                ARGV[2]
            )

            if not acquired then
                return 0
            end

            -- 抢锁成功后才能撤销 ready 和删除当前片。
            redis.call('DEL', KEYS[2], KEYS[3])
            return 1
            """,
            Long.class
    );

    /**
     * 带所有权校验的批量写入
     * KEYS[1] = rebuild lock
     * KEYS[2] = 当前 Bitmap
     * ARGV[1] = token
     * ARGV[2] = Bitmap TTL
     * ARGV[3...] = bit offsets
     */
    private static final DefaultRedisScript<Long> SET_REBUILD_BITS_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then
                return -1
            end

            for index = 3, #ARGV do
                redis.call(
                    'SETBIT',
                    KEYS[2],
                    tonumber(ARGV[index]),
                    1
                )
            end

            -- 即使重建中途失败，残留的部分 Bitmap 最终也会过期。
            redis.call('PEXPIRE', KEYS[2], ARGV[2])
            return #ARGV - 2
            """,
            Long.class
    );

    /**
     * 原子发布 ready
     * KEYS[1] = rebuild lock
     * KEYS[2] = Bitmap
     * KEYS[3] = ZSET registry
     * KEYS[4] = ready
     * ARGV[1] = token
     * ARGV[2] = slice ID
     * ARGV[3] = Bitmap TTL
     * ARGV[4] = ready TTL
     */
    private static final DefaultRedisScript<Long> PUBLISH_REBUILD_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then
                return 0
            end

            -- 空数据集时才创建一个全 0 Bitmap。
            -- 如果增量写已经创建了 Bitmap，绝对不能清除 bit 0。
            if redis.call('EXISTS', KEYS[2]) == 0 then
                redis.call('SETBIT', KEYS[2], 0, 0)
            end

            redis.call('PEXPIRE', KEYS[2], ARGV[3])
            redis.call(
                'ZADD',
                KEYS[3],
                tonumber(ARGV[2]),
                ARGV[2]
            )

            -- ready 必须在所有关键数据写完后发布。
            redis.call(
                'SET',
                KEYS[4],
                ARGV[2],
                'PX',
                ARGV[4]
            )

            -- 发布成功后原子释放锁。
            redis.call('DEL', KEYS[1])
            return 1
            """,
            Long.class
    );

    /**
     * 只有 token 仍属于当前实例时，才允许撤销重建并释放锁。
     * <p>
     * 不能直接 DEL 锁：旧实例的锁过期后，另一个实例可能已经拿到了新锁。
     */
    private static final DefaultRedisScript<Long> ABORT_REBUILD_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then
                return 0
            end

            redis.call('DEL', KEYS[2], KEYS[3], KEYS[1])
            return 1
            """,
            Long.class
    );

    private final AtomicBoolean bloomTrusted = new AtomicBoolean(false);
    /**
     * 读写 Redis 失败后，旧 ready 可能对应一个遗漏了新短码的 Bitmap。
     * dirty 只能由完整重建成功清除，不能被一次 GET ready 清除。
     */
    private final AtomicBoolean bloomDirty = new AtomicBoolean(false);

    private final StringRedisTemplate redisTemplate;
    private final BloomFilterParameters parameters;
    private final BloomSliceWindow sliceWindow;
    private final Duration negativeTtl;
    private final Duration negativeJitter;

    public RedisShortLinkProtection(
            StringRedisTemplate redisTemplate,
            @Value("${notification.shortlink.bloom.expected-insertions:100000}") long expectedInsertions,
            @Value("${notification.shortlink.bloom.overall-false-positive-probability:0.01}") double overallFalsePositiveProbability,
            @Value("${notification.shortlink.bloom.slice-duration:PT6H}") Duration sliceDuration,
            @Value("${notification.shortlink.bloom.retained-slice-count:4}") int retainedSliceCount,
            @Value("${notification.shortlink.negative-cache.ttl:PT2M}") Duration negativeTtl,
            @Value("${notification.shortlink.negative-cache.jitter:PT30S}") Duration negativeJitter,
            @Qualifier("bloomClock") Clock clock
    ) {
        if (negativeTtl == null || negativeTtl.isZero() || negativeTtl.isNegative()) {
            throw new IllegalArgumentException("negativeTtl must be positive");
        }
        if (negativeJitter == null || negativeJitter.isNegative()) {
            throw new IllegalArgumentException("negativeJitter must not be negative");
        }

        this.redisTemplate = redisTemplate;
        this.parameters = BloomFilterParameters.calculate(
                expectedInsertions, // 预期插入量
                overallFalsePositiveProbability, // 误判率
                retainedSliceCount // 保留的片数
        );
        this.sliceWindow = new BloomSliceWindow(
                clock,
                sliceDuration,
                retainedSliceCount
        );
        this.negativeTtl = negativeTtl;
        this.negativeJitter = negativeJitter;

        log.info(
                "time-sliced bloom configured, expectedInsertions={}, bitSize={}, hashFunctions={}, perSliceFpp={}, overallFpp={}",
                parameters.expectedInsertions(),
                parameters.bitSize(),
                parameters.hashFunctions(),
                parameters.perSliceFalsePositiveProbability(),
                parameters.overallFalsePositiveProbability()
        );
    }

    @Override
    public Optional<ShortLinkNegativeReason> getNegative(final String shortCode) {
        try {
            String value = redisTemplate.opsForValue().get(
                    ShortLinkRedisKeys.negative(shortCode)
            );
            if (value == null) {
                return Optional.empty();
            }
            return Optional.of(ShortLinkNegativeReason.valueOf(value));
        } catch (IllegalArgumentException exception) {
            log.warn("invalid short-link negative cache, shortCode={}", shortCode, exception);
            evictNegative(shortCode);
            return Optional.empty();
        } catch (RuntimeException exception) {
            log.warn("read short-link negative cache failed, shortCode={}", shortCode, exception);
            return Optional.empty();
        }
    }

    @Override
    public void cacheNegative(final String shortCode, final ShortLinkNegativeReason reason) {
        try {
            redisTemplate.opsForValue().set(
                    ShortLinkRedisKeys.negative(shortCode),
                    reason.name(),
                    negativeTtl.plusMillis(randomJitterMillis())
            );
        } catch (RuntimeException exception) {
            log.warn("write short-link negative cache failed, shortCode={}", shortCode, exception);
        }
    }

    @Override
    public void evictNegative(final String shortCode) {
        try {
            redisTemplate.delete(ShortLinkRedisKeys.negative(shortCode));
        } catch (RuntimeException exception) {
            log.warn("evict short-link negative cache failed, shortCode={}", shortCode, exception);
        }
    }

    @Override
    public boolean isBloomReady() {
        if (bloomDirty.get()) {
            return false;
        }

        long currentSlice = sliceWindow.currentSliceStart();
        String currentValue = Long.toString(currentSlice);
        String currentBitmap = ShortLinkRedisKeys.bloomSlice(currentSlice);

        try {
            String readyValue = redisTemplate.opsForValue().get(BLOOM_READY_KEY);
            Boolean bitmapExists = redisTemplate.hasKey(currentBitmap);
            boolean trusted = currentValue.equals(readyValue) && bitmapExists;
            bloomTrusted.set(trusted);
            return trusted;
        } catch (RuntimeException exception) {
            bloomTrusted.set(false);
            bloomDirty.set(true);
            log.warn("refresh bloom trusted state failed", exception);
            return false;
        }
    }

    @Override
    public boolean mightContain(final String shortCode) {
        // 其他实例可能已经完成重建，因此本机false时先尝试恢复信任
        if (!bloomTrusted.get() && !isBloomReady()) {
            return true;
        }

        List<String> keys = new ArrayList<>();
        keys.add(BLOOM_READY_KEY);
        for (final var sliceStart : sliceWindow.retainedSliceStarts()) {
            keys.add(ShortLinkRedisKeys.bloomSlice(sliceStart));
        }

        String[] offsets = bloomOffsets(shortCode);
        String[] arguments = new String[offsets.length + 1];
        arguments[0] = Long.toString(sliceWindow.currentSliceStart());
        System.arraycopy(offsets, 0, arguments, 1, offsets.length);

        try {
            Long result = redisTemplate.execute(
                    CHECK_SLICES_SCRIPT,
                    keys,
                    (Object[]) arguments
            );
            if (result == null || result == 2L) {
                bloomTrusted.set(false);
                return true;
            }
            return result == 1L;
        } catch (RuntimeException exception) {
            bloomTrusted.set(false);
            bloomDirty.set(true);
            log.warn("check time-sliced bloom failed, shortCode={}", shortCode, exception);
            return true;
        }
    }

    @Override
    public void addToBloom(final String shortCode) {
        long currentSlice = sliceWindow.currentSliceStart();
        String bitmapKey = ShortLinkRedisKeys.bloomSlice(currentSlice);
        try {
            setOffsets(bitmapKey, List.of(bloomOffsets(shortCode)));
            redisTemplate.expire(bitmapKey, sliceWindow.bitmapTtl());
        } catch (RuntimeException exception) {
            bloomTrusted.set(false);
            bloomDirty.set(true);
            log.warn("add short code to current bloom slice failed, shortCode={}",
                    shortCode, exception);
            clearBloomReadySafely();
        }
    }

    @Override
    public boolean beginBloomRebuild() {
        bloomTrusted.set(false);

        long currentSlice = sliceWindow.currentSliceStart();
        String bitmapKey = ShortLinkRedisKeys.bloomSlice(currentSlice);
        RebuildContext context = new RebuildContext(
                UUID.randomUUID().toString(),
                currentSlice,
                bitmapKey
        );

        // 除了分布式锁，再防止本机调用者绕过初始化器重复发起重建。
        if (!activeRebuild.compareAndSet(null, context)) {
            return false;
        }

        try {
            Long acquired = redisTemplate.execute(
                    BEGIN_REBUILD_SCRIPT,
                    List.of(
                            BLOOM_REBUILD_LOCK_KEY,
                            BLOOM_READY_KEY,
                            bitmapKey
                    ),
                    context.token(),
                    Long.toString(REBUILD_LOCK_TTL.toMillis())
            );

            if (acquired == null || acquired != 1L) {
                activeRebuild.compareAndSet(context, null);
                return false;
            }

            // 抢锁成功且 ready 已撤销；成功发布前，本机只能 fail-open。
            bloomDirty.set(true);
            return true;
        } catch (RuntimeException exception) {
            log.warn("begin time-sliced bloom rebuild failed", exception);

            /*
             * Lua 可能已经执行成功，只是响应在网络中丢失。
             * 使用 token 中止既能释放自己的锁，也不会删除其他实例的新锁。
             */
            abortBloomRebuild(context);
            return false;
        }
    }

    @Override
    public boolean completeBloomRebuild(Collection<String> shortCodes) {
        bloomTrusted.set(false);
        bloomDirty.set(true);

        RebuildContext context = activeRebuild.get();
        if (context == null) {
            log.warn("complete bloom rebuild called without active context");
            return false;
        }

        long actualSlice = sliceWindow.currentSliceStart();
        if (actualSlice != context.sliceStart()) {
            log.warn(
                    "bloom slice changed during rebuild, expected={}, actual={}",
                    context.sliceStart(),
                    actualSlice
            );
            abortBloomRebuild(context);
            return false;
        }

        String currentSliceId = Long.toString(context.sliceStart());

        try {
            writeRebuildBatches(context, shortCodes);

            Long published = redisTemplate.execute(
                    PUBLISH_REBUILD_SCRIPT,
                    List.of(
                            BLOOM_REBUILD_LOCK_KEY,
                            context.bitmapKey(),
                            BLOOM_REGISTRY_KEY,
                            BLOOM_READY_KEY
                    ),
                    context.token(),
                    currentSliceId,
                    Long.toString(sliceWindow.bitmapTtl().toMillis()),
                    Long.toString(sliceWindow.readyTtl().toMillis())
            );

            if (published == null || published != 1L) {
                log.warn("publish bloom rebuild rejected because lock ownership was lost");
                abortBloomRebuild(context);
                return false;
            }

            // 发布 Lua 已经原子释放 Redis 锁，这里只清理本机上下文。
            activeRebuild.compareAndSet(context, null);
            cleanupExpiredSlicesSafely();
            bloomDirty.set(false);
            bloomTrusted.set(true);
            log.info("time-sliced bloom rebuilt, slice={}, count={}", currentSliceId, shortCodes.size());
            return true;
        } catch (RuntimeException exception) {
            bloomTrusted.set(false);
            bloomDirty.set(true);
            log.error("complete time-sliced bloom rebuild failed", exception);
            abortBloomRebuild(context);
            return false;
        }
    }

    @Override
    public void abortBloomRebuild() {
        RebuildContext context = activeRebuild.get();
        if (context != null) {
            abortBloomRebuild(context);
        }
    }

    private void abortBloomRebuild(RebuildContext context) {
        if (!activeRebuild.compareAndSet(context, null)) {
            return;
        }

        bloomTrusted.set(false);
        bloomDirty.set(true);

        try {
            redisTemplate.execute(
                    ABORT_REBUILD_SCRIPT,
                    List.of(
                            BLOOM_REBUILD_LOCK_KEY,
                            BLOOM_READY_KEY,
                            context.bitmapKey()
                    ),
                    context.token()
            );
        } catch (RuntimeException exception) {
            // 无法释放时依靠锁 TTL；绝不能无条件删除可能属于其他实例的锁。
            log.warn("abort bloom rebuild failed; lock TTL will recover it", exception);
        }
    }

    private void writeRebuildBatches(
            RebuildContext context,
            Collection<String> shortCodes
    ) {
        List<String> batchOffsets = new ArrayList<>(
                REBUILD_BATCH_CODES * parameters.hashFunctions()
        );
        int codesInBatch = 0;

        for (String shortCode : shortCodes) {
            if (shortCode == null || shortCode.isBlank()) {
                continue;
            }
            batchOffsets.addAll(List.of(bloomOffsets(shortCode)));
            codesInBatch++;

            if (codesInBatch == REBUILD_BATCH_CODES) {
                setRebuildOffsets(context, batchOffsets);
                batchOffsets.clear();
                codesInBatch = 0;
            }
        }

        if (!batchOffsets.isEmpty()) {
            setRebuildOffsets(context, batchOffsets);
        }
    }

    private void setRebuildOffsets(
            RebuildContext context,
            Collection<String> offsets
    ) {
        List<String> arguments = new ArrayList<>(offsets.size() + 2);
        arguments.add(context.token());
        arguments.add(Long.toString(sliceWindow.bitmapTtl().toMillis()));
        arguments.addAll(offsets);

        Long affectedBits = redisTemplate.execute(
                SET_REBUILD_BITS_SCRIPT,
                List.of(
                        BLOOM_REBUILD_LOCK_KEY,
                        context.bitmapKey()
                ),
                arguments.toArray()
        );

        if (affectedBits == null || affectedBits.longValue() != offsets.size()) {
            throw new IllegalStateException(
                    "bloom rebuild lock lost or bit write failed"
            );
        }
    }

    private void setOffsets(String bitmapKey, Collection<String> offsets) {
        Long affectedBits = redisTemplate.execute(
                SET_BITS_SCRIPT,
                List.of(bitmapKey),
                offsets.toArray()
        );
        if (affectedBits == null || affectedBits.longValue() != offsets.size()) {
            throw new IllegalStateException("set bloom bits returned unexpected result");
        }
    }

    private void cleanupExpiredSlicesSafely() {
        try {
            long oldestRetained = sliceWindow.oldestRetainedSliceStart();
            Set<String> expiredSliceIds = redisTemplate.opsForZSet().rangeByScore(
                    BLOOM_REGISTRY_KEY,
                    Double.NEGATIVE_INFINITY,
                    oldestRetained - 1.0
            );
            if (expiredSliceIds == null || expiredSliceIds.isEmpty()) {
                return;
            }

            List<String> expiredBitmapKeys = expiredSliceIds.stream()
                    .map(Long::parseLong)
                    .map(ShortLinkRedisKeys::bloomSlice)
                    .toList();

            redisTemplate.delete(expiredBitmapKeys);
            redisTemplate.opsForZSet().remove(
                    BLOOM_REGISTRY_KEY,
                    expiredSliceIds.toArray()
            );
            log.info("expired bloom slices cleaned, count={}", expiredSliceIds.size());
        } catch (RuntimeException exception) {
            // TTL 仍会兜底，清理失败不能让完整的当前片变得不可用。
            log.warn("cleanup expired bloom slices failed", exception);
        }
    }

    private void clearBloomReadySafely() {
        try {
            redisTemplate.delete(BLOOM_READY_KEY);
        } catch (RuntimeException cleanupException) {
            log.warn("clear bloom ready flag failed", cleanupException);
        }
    }

    private String[] bloomOffsets(String shortCode) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(
                    shortCode.getBytes(StandardCharsets.UTF_8)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }

        ByteBuffer buffer = ByteBuffer.wrap(digest);
        long firstHash = buffer.getLong();
        long secondHash = buffer.getLong();
        if (secondHash == 0L) {
            secondHash = 0x9E3779B97F4A7C15L;
        }

        String[] offsets = new String[parameters.hashFunctions()];
        for (int index = 0; index < offsets.length; index++) {
            long combinedHash = firstHash + index * secondHash;
            offsets[index] = Long.toString(
                    Math.floorMod(combinedHash, parameters.bitSize())
            );
        }
        return offsets;
    }

    private long randomJitterMillis() {
        long upperBound = negativeJitter.toMillis();
        return upperBound <= 0L
                ? 0L
                : ThreadLocalRandom.current().nextLong(upperBound + 1L);
    }
}
```

## 5.8 完整修改 ShortLinkBloomInitializer.java

初始化器只在成功发布后记录完成；MySQL 扫描异常时会安全中止自己持有的重建。

```java
package com.tam.notification.config;

import com.tam.notification.domain.shortlink.ShortLinkMappingRepository;
import com.tam.notification.domain.shortlink.ShortLinkProtection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 启动预热，并在运行期间检测 UTC 时间片轮换。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE) // 优先级最高
public class ShortLinkBloomInitializer implements ApplicationRunner {

    private final ShortLinkMappingRepository mappingRepository;
    private final ShortLinkProtection shortLinkProtection;
    private final AtomicBoolean rebuilding = new AtomicBoolean(false);

    public ShortLinkBloomInitializer(
            ShortLinkMappingRepository mappingRepository,
            ShortLinkProtection shortLinkProtection
    ) {
        this.mappingRepository = mappingRepository;
        this.shortLinkProtection = shortLinkProtection;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureCurrentSliceReady();
    }

    @Scheduled(fixedDelayString = "${notification.shortlink.bloom.rebuild-check-interval-ms:60000}")
    public void ensureCurrentSliceReady() {
        if (shortLinkProtection.isBloomReady()) {
            return;
        }

        // 防止本机的启动线程与调度线程重复扫描数据库。
        if (!rebuilding.compareAndSet(false, true)) {
            return;
        }

        boolean rebuildStarted = false;

        try {
            // CAS 等待期间，另一个执行者可能已经完成。
            if (shortLinkProtection.isBloomReady()) {
                return;
            }
            if (!shortLinkProtection.beginBloomRebuild()) {
                return;
            }
            rebuildStarted = true;

            List<String> shortCodes = mappingRepository.findAllActiveShortCodesAcrossTenants();
            boolean completed = shortLinkProtection.completeBloomRebuild(shortCodes);
            // complete 已负责发布或安全中止，避免 catch 中重复操作。
            rebuildStarted = false;

            if (!completed) {
                log.warn("short-link bloom rebuild was not published");
                return;
            }

            log.info("short-link bloom current slice initialized, count={}", shortCodes.size());
        } catch (RuntimeException exception) {
            if (rebuildStarted) {
                shortLinkProtection.abortBloomRebuild();
            }
            // ready 缺失时查询自动放行 MySQL，不能阻止应用启动。
            log.error("initialize current bloom slice failed", exception);
        } finally {
            rebuilding.set(false);
        }
    }
}
```

## 5.9 修改 application.yml

用下面内容替换现有 `notification.shortlink.bloom`：

```yaml
notification:
  shortlink:
    bloom:
      # 先以 10 万个仍有效短码做小规模可验证实验。
      expected-insertions: 100000
      # 这是查询全部保留片后的总体目标，不是每片目标。
      overall-false-positive-probability: 0.01
      # ISO-8601 Duration；生产默认每 6 小时轮换。
      slice-duration: PT6H
      # 当前片 + 3 个历史片，总窗口 24 小时。
      retained-slice-count: 4
      # 每分钟检查一次是否跨入新片或 ready 丢失。
      rebuild-check-interval-ms: 60000
```

## 5.10 修改 ShortLinkRedisKeysTest.java

锁、ready、注册表和所有时间片必须共享 `{bloom:v3}` Hash Tag。

```java
package com.tam.notification.shortlink;

import com.tam.notification.redis.RedisClusterSlot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ShortLinkRedisKeysTest {
    @Test
    void sameShortCodeKeysShouldUseSameSlotAndStableFormat() {
        String code = "aZ8k2LmP";
        String redirect = ShortLinkRedisKeys.redirect(code);

        assertEquals("shortlink:{aZ8k2LmP}:redirect", redirect);
        assertEquals("shortlink:{aZ8k2LmP}:click:count",
                ShortLinkRedisKeys.clickCount(code));
        assertEquals(RedisClusterSlot.slot(redirect),
                RedisClusterSlot.slot(ShortLinkRedisKeys.negative(code)));
        assertEquals(RedisClusterSlot.slot(redirect),
                RedisClusterSlot.slot(ShortLinkRedisKeys.clickCount(code)));
    }

    @Test
    void allBloomV3KeysShouldUseSameSlot() {
        int expectedSlot = RedisClusterSlot.slot(ShortLinkRedisKeys.bloomReady());

        assertEquals(expectedSlot,
                RedisClusterSlot.slot(ShortLinkRedisKeys.bloomSliceRegistry()));
        assertEquals(expectedSlot,
                RedisClusterSlot.slot(ShortLinkRedisKeys.bloomSlice(1723809600L)));
        assertEquals(expectedSlot,
                RedisClusterSlot.slot(ShortLinkRedisKeys.bloomSlice(1723831200L)));

        assertEquals(
                expectedSlot,
                RedisClusterSlot.slot(ShortLinkRedisKeys.bloomRebuildLock())
        );
    }

    @Test
    void differentShortCodesShouldRemainDistributable() {
        assertNotEquals(
                RedisClusterSlot.slot(ShortLinkRedisKeys.redirect("aZ8k2LmP")),
                RedisClusterSlot.slot(ShortLinkRedisKeys.redirect("Xy98Mn76"))
        );
    }

    @Test
    void bracesMustNotEnterHashTag() {
        assertThrows(IllegalArgumentException.class,
                () -> ShortLinkRedisKeys.redirect("bad{code}"));
    }
}
```

## 5.11 新增参数与窗口单元测试

`BloomFilterParametersTest.java`：

```java
package com.tam.notification.shortlink;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BloomFilterParametersTest {

    @Test
    void shouldCalculateParametersFromOverallProbability() {
        BloomFilterParameters parameters =
                BloomFilterParameters.calculate(100_000, 0.01, 4);

        assertEquals(1_246_262L, parameters.bitSize());
        assertEquals(9, parameters.hashFunctions());
        assertEquals(0.002509430066318874,
                parameters.perSliceFalsePositiveProbability(), 1.0E-15);

        double reconstructedOverall = 1.0 - Math.pow(
                1.0 - parameters.perSliceFalsePositiveProbability(),
                4
        );
        assertEquals(0.01, reconstructedOverall, 1.0E-12);
    }

    @Test
    void shouldRejectInvalidInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> BloomFilterParameters.calculate(0, 0.01, 4));
        assertThrows(IllegalArgumentException.class,
                () -> BloomFilterParameters.calculate(100, 1.0, 4));
        assertThrows(IllegalArgumentException.class,
                () -> BloomFilterParameters.calculate(100, 0.01, 0));
    }
}
```

`BloomSliceWindowTest.java`：

```java
package com.tam.notification.shortlink;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BloomSliceWindowTest {

    @Test
    void shouldAlignToUtcSliceBoundaryAndReturnHistory() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-08-16T11:59:59Z"),
                ZoneOffset.UTC
        );
        BloomSliceWindow window = new BloomSliceWindow(
                clock,
                Duration.ofHours(6),
                4
        );

        long current = Instant.parse("2026-08-16T06:00:00Z").getEpochSecond();
        assertEquals(current, window.currentSliceStart());
        assertEquals(List.of(
                current,
                current - Duration.ofHours(6).getSeconds(),
                current - Duration.ofHours(12).getSeconds(),
                current - Duration.ofHours(18).getSeconds()
        ), window.retainedSliceStarts());
        assertEquals(Duration.ofHours(30), window.bitmapTtl());
    }
}
```

## 5.12 新增 fail-open 单元测试

```java
package com.tam.notification.shortlink;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class RedisShortLinkProtectionFailOpenTest {

    @SuppressWarnings("unchecked")
    @Test
    void redisFailureMustNotRejectLegalShortCode() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenThrow(
                new RedisConnectionFailureException("redis down")
        );

        RedisShortLinkProtection protection = new RedisShortLinkProtection(
                redis,
                10_000,
                0.01,
                Duration.ofHours(1),
                4,
                Duration.ofMinutes(2),
                Duration.ofSeconds(30),
                Clock.systemUTC()
        );

        // true 的含义是“可能存在，继续查 MySQL”。
        assertTrue(protection.mightContain("Ab12Cd34"));
    }
}
```

## 5.13 新增启动预热编排测试

除了成功重建，还验证 MySQL 扫描失败后一定调用安全中止。

```java
package com.tam.notification.shortlink;

import com.tam.notification.config.ShortLinkBloomInitializer;
import com.tam.notification.domain.shortlink.ShortLinkMappingRepository;
import com.tam.notification.domain.shortlink.ShortLinkProtection;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

import java.util.List;

import static org.mockito.Mockito.*;

class ShortLinkBloomInitializerTest {

    @Test
    void startupShouldLoadOnlyActiveCodesAndCompleteRebuild() {
        ShortLinkMappingRepository repository = mock(ShortLinkMappingRepository.class);
        ShortLinkProtection protection = mock(ShortLinkProtection.class);
        when(protection.isBloomReady()).thenReturn(false);
        when(protection.beginBloomRebuild()).thenReturn(true);
        when(repository.findAllActiveShortCodesAcrossTenants())
                .thenReturn(List.of("Ab12Cd34", "Ef56Gh78"));
        when(protection.completeBloomRebuild(
                List.of("Ab12Cd34", "Ef56Gh78")
        )).thenReturn(true);

        ShortLinkBloomInitializer initializer = new ShortLinkBloomInitializer(repository, protection);
        initializer.run(mock(ApplicationArguments.class));

        verify(repository).findAllActiveShortCodesAcrossTenants();
        verify(protection).completeBloomRebuild(
                List.of("Ab12Cd34", "Ef56Gh78")
        );
    }

    @Test
    void databaseFailureShouldAbortOwnedRebuild() {
        ShortLinkMappingRepository repository = mock(ShortLinkMappingRepository.class);
        ShortLinkProtection protection = mock(ShortLinkProtection.class);
        when(protection.isBloomReady()).thenReturn(false);
        when(protection.beginBloomRebuild()).thenReturn(true);
        when(repository.findAllActiveShortCodesAcrossTenants())
                .thenThrow(new IllegalStateException("mysql unavailable"));

        ShortLinkBloomInitializer initializer = new ShortLinkBloomInitializer(repository, protection);
        initializer.run(mock(ApplicationArguments.class));

        verify(protection).abortBloomRebuild();
        verify(protection, never()).completeBloomRebuild(anyCollection());
    }
}
```

## 5.14 新增完整真实 Cluster 集成测试

真实六节点 Redis Cluster 覆盖分片轮换、总体误判率、内存占用、锁安全中止、锁过期拒绝，以及两个实例在第 500 条边界交错重建的回归场景。

```java
package com.tam.notification.shortlink;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.*;
import java.util.AbstractCollection;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "REDIS_CLUSTER_NODES", matches = ".+")
class RedisTimeSlicedBloomFilterIntegrationTest {

    private static final DefaultRedisScript<Long> MEMORY_USAGE_SCRIPT =
            new DefaultRedisScript<>(
                    "return redis.call('MEMORY', 'USAGE', KEYS[1])",
                    Long.class
            );

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    @BeforeAll
    static void connectCluster() {
        List<String> nodes = List.of(
                System.getenv("REDIS_CLUSTER_NODES").split(",")
        );
        RedisClusterConfiguration configuration =
                new RedisClusterConfiguration(nodes);
        configuration.setPassword(RedisPassword.of(
                System.getenv().getOrDefault(
                        "REDIS_CLUSTER_PASSWORD",
                        "notification123"
                )
        ));
        configuration.setMaxRedirects(5);

        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @BeforeEach
    void cleanBeforeTest() {
        cleanBloomV3Keys();
    }

    @AfterEach
    void cleanAfterTest() {
        cleanBloomV3Keys();
    }

    @AfterAll
    static void closeClusterConnection() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void rebuildShouldKeepExistingCodesAndFailOpenBeforeReady() {
        MutableClock clock = new MutableClock(
                Instant.parse("2026-08-16T00:00:00Z")
        );
        RedisShortLinkProtection protection = protection(clock, 10_000, 4);

        assertTrue(protection.beginBloomRebuild());

        // begin 已删除 ready，合法短码必须被放行，而不是返回 false。
        assertTrue(protection.mightContain("Ab12Cd34"));

        assertTrue(protection.completeBloomRebuild(List.of("Ab12Cd34", "Ef56Gh78")));
        assertTrue(protection.isBloomReady());
        assertTrue(protection.mightContain("Ab12Cd34"));
        assertTrue(protection.mightContain("Ef56Gh78"));
    }

    @Test
    void rotationShouldQueryHistoryAndDeleteExpiredSlice() {
        MutableClock clock = new MutableClock(
                Instant.parse("2026-08-16T00:00:00Z")
        );
        RedisShortLinkProtection protection = protection(clock, 10_000, 4);
        long firstSlice = clock.instant().getEpochSecond();

        // 连续建立 4 个片；每片放一个固定样本，验证历史片查询。
        for (int slice = 0; slice < 4; slice++) {
            assertTrue(protection.beginBloomRebuild());
            assertTrue(protection.completeBloomRebuild(List.of("slice-code-" + slice)));
            if (slice < 3) {
                clock.advance(Duration.ofHours(1));
            }
        }

        assertTrue(protection.mightContain("slice-code-0"));
        assertEquals(
                Boolean.TRUE,
                redisTemplate.hasKey(ShortLinkRedisKeys.bloomSlice(firstSlice))
        );

        // 第 5 片完成后，第 1 片已经落在 4 片窗口之外。
        clock.advance(Duration.ofHours(1));
        assertTrue(protection.beginBloomRebuild());
        assertTrue(protection.completeBloomRebuild(List.of("slice-code-4")));

        assertEquals(
                Boolean.FALSE,
                redisTemplate.hasKey(ShortLinkRedisKeys.bloomSlice(firstSlice))
        );
    }

    @Test
    void measuredFalsePositiveRateShouldStayNearConfiguredTarget() {
        MutableClock clock = new MutableClock(
                Instant.parse("2026-08-16T00:00:00Z")
        );
        RedisShortLinkProtection protection = protection(clock, 2_000, 4);

        // 给 4 个片写入互不相同的样本，覆盖“查询片并集”的误判率。
        for (int slice = 0; slice < 4; slice++) {
            int sliceIndex = slice; // Lambda 只能捕获 effectively-final 变量。
            List<String> codes = IntStream.range(0, 2_000)
                    .mapToObj(index ->
                            "present-" + sliceIndex + "-" + index)
                    .toList();

            assertTrue(protection.beginBloomRebuild());
            assertTrue(protection.completeBloomRebuild(codes));
            if (slice < 3) {
                clock.advance(Duration.ofHours(1));
            }
        }

        int samples = 20_000;
        long falsePositives = IntStream.range(0, samples)
                .filter(index -> protection.mightContain("absent-" + index))
                .count();
        double actualRate = falsePositives / (double) samples;
        List<Long> bitmapMemoryBytes = IntStream.range(0, 4)
                .mapToObj(index -> ShortLinkRedisKeys.bloomSlice(
                        clock.instant().minus(Duration.ofHours(index)).getEpochSecond()
                ))
                .map(this::memoryUsage)
                .toList();

        System.out.printf(
                "samples=%d, falsePositives=%d, actualRate=%.6f, bitmapMemoryBytes=%s%n",
                samples,
                falsePositives,
                actualRate,
                bitmapMemoryBytes
        );

        // 小样本允许波动；实际值必须记录，不能只口头声称约 1%。
        assertTrue(
                actualRate <= 0.02,
                "actual false positive rate is too high: " + actualRate
        );
    }

    private long memoryUsage(String key) {
        Long result = redisTemplate.execute(
                MEMORY_USAGE_SCRIPT,
                List.of(key)
        );

        if (result != null) {
            return result;
        }
        throw new IllegalStateException("MEMORY USAGE returned: " + result);
    }

    @Test
    void concurrentRebuildShouldAllowOnlyLockOwnerToPublish() throws Exception {
        MutableClock clock = new MutableClock(
                Instant.parse("2026-08-16T00:00:00Z")
        );
        RedisShortLinkProtection first = protection(clock, 10_000, 4);
        RedisShortLinkProtection second = protection(clock, 10_000, 4);

        CountDownLatch firstBatchWritten = new CountDownLatch(1);
        CountDownLatch allowFirstToFinish = new CountDownLatch(1);
        CollectionWithPause codes = new CollectionWithPause(
                1_000,
                500,
                firstBatchWritten,
                allowFirstToFinish
        );

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertTrue(first.beginBloomRebuild());
            Future<Boolean> firstResult = executor.submit(
                    () -> first.completeBloomRebuild(codes)
            );

            assertTrue(
                    firstBatchWritten.await(5, TimeUnit.SECONDS),
                    "first rebuild did not reach the batch boundary"
            );

            // 第二个实例不能删除第一个实例已经写好的批次。
            assertFalse(second.beginBloomRebuild());

            allowFirstToFinish.countDown();
            assertTrue(firstResult.get(5, TimeUnit.SECONDS));

            // 同时检查暂停点前后的数据，证明发布的是完整快照。
            assertTrue(first.mightContain("concurrent-code-0"));
            assertTrue(first.mightContain("concurrent-code-999"));
        } finally {
            allowFirstToFinish.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void abortShouldReleaseOwnedLockAndAllowEmptySnapshotRebuild() {
        MutableClock clock = new MutableClock(
                Instant.parse("2026-08-16T00:00:00Z")
        );
        RedisShortLinkProtection first = protection(clock, 10_000, 4);
        RedisShortLinkProtection second = protection(clock, 10_000, 4);

        assertTrue(first.beginBloomRebuild());
        first.abortBloomRebuild();

        // 中止脚本释放自己的 token 后，另一个实例可以立即接管。
        assertTrue(second.beginBloomRebuild());
        assertTrue(second.completeBloomRebuild(List.of()));

        // 空快照也必须创建可信的当前 Bitmap，而不能永久 fail-open。
        assertTrue(second.isBloomReady());
    }

    @Test
    void instanceThatLostItsTokenMustNotPublishReady() {
        MutableClock clock = new MutableClock(
                Instant.parse("2026-08-16T00:00:00Z")
        );
        RedisShortLinkProtection protection = protection(clock, 10_000, 4);

        assertTrue(protection.beginBloomRebuild());

        // 模拟锁 TTL 到期；旧实例的批次写入和 ready 发布都必须被拒绝。
        redisTemplate.delete(ShortLinkRedisKeys.bloomRebuildLock());
        assertFalse(protection.completeBloomRebuild(List.of("must-not-publish")));
        assertEquals(Boolean.FALSE, redisTemplate.hasKey(ShortLinkRedisKeys.bloomReady()));
    }

    private RedisShortLinkProtection protection(
            Clock clock,
            long expectedInsertions,
            int retainedSlices
    ) {
        return new RedisShortLinkProtection(
                redisTemplate,
                expectedInsertions,
                0.01,
                Duration.ofHours(1),
                retainedSlices,
                Duration.ofMinutes(2),
                Duration.ZERO,
                clock
        );
    }

    private void cleanBloomV3Keys() {
        String registryKey = ShortLinkRedisKeys.bloomSliceRegistry();
        Set<String> sliceIds = redisTemplate.opsForZSet().range(
                registryKey,
                0,
                -1
        );

        if (sliceIds != null && !sliceIds.isEmpty()) {
            List<String> bitmapKeys = sliceIds.stream()
                    .map(Long::parseLong)
                    .map(ShortLinkRedisKeys::bloomSlice)
                    .toList();
            redisTemplate.delete(bitmapKeys);
        }

        // 所有 Key 共享 {bloom:v3}，一次 DEL 不会产生 CROSSSLOT。
        redisTemplate.delete(List.of(
                ShortLinkRedisKeys.bloomReady(),
                ShortLinkRedisKeys.bloomRebuildLock(),
                registryKey
        ));
    }

    /**
     * 让第一个重建者在第 500 条已经写入 Redis 后暂停。
     * 此时第二个实例尝试重建，可以稳定复现原实现的 DEL 竞态。
     */
    private static final class CollectionWithPause extends AbstractCollection<String> {
        private final int size;
        private final int pauseAfter;
        private final CountDownLatch paused;
        private final CountDownLatch resume;

        private CollectionWithPause(
                int size,
                int pauseAfter,
                CountDownLatch paused,
                CountDownLatch resume
        ) {
            this.size = size;
            this.pauseAfter = pauseAfter;
            this.paused = paused;
            this.resume = resume;
        }

        @Override
        public Iterator<String> iterator() {
            return new Iterator<>() {
                private int index;
                private boolean pauseCompleted;

                @Override
                public boolean hasNext() {
                    if (index == pauseAfter && !pauseCompleted) {
                        pauseCompleted = true;
                        paused.countDown();
                        awaitResume();
                    }
                    return index < size;
                }

                @Override
                public String next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }
                    return "concurrent-code-" + index++;
                }

                private void awaitResume() {
                    try {
                        if (!resume.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException(
                                    "timed out waiting to resume rebuild"
                            );
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(
                                "rebuild test interrupted",
                                exception
                        );
                    }
                }
            };
        }

        @Override
        public int size() {
            return size;
        }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException(
                        "test clock only supports UTC"
                );
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
```

## 5.15 新增 docs/bloom-lifecycle.md

````markdown
# Short Link Bloom Lifecycle

## 决策

- 只使用 Redis Cluster 共享时间分片，不增加本地 Bloom 和 Redis Stream。
- UTC 6 小时一片，默认保留 4 片。
- 每次轮换把 MySQL 中 ACTIVE 且未过期的短码完整重建到当前片。
- 新短链在事务提交后增量写当前片。
- ready 必须最后写；ready 不可信、重建中或 Redis 故障时一律 fail-open。
- 完整重建使用同槽分布式锁；抢锁、撤销 ready、分批写入、发布和中止均校验随机 token。

## 参数口径

- expectedInsertions：100000 个仍有效短码/完整快照片。
- overallFalsePositiveProbability：查询全部保留片后的 1%。
- retainedSliceCount：4。
- 计算结果：每片 1246262 bits、9 个哈希位置、约 152.13 KiB。

## 生命周期

1. Lua 原子抢占带 TTL 的 token 锁，并删除 ready 和当前片。
2. 查询 MySQL 有效短码。
3. 每 500 个短码校验 token 后批量写 Bitmap。
4. Lua 再次校验 token，设置 Bitmap TTL 并登记 ZSET。
5. 在同一个 Lua 中写 ready=currentSlice，并释放重建锁。
6. 删除窗口外片；TTL 作为兜底。
7. MySQL 扫描、写入或发布失败时，比较 token 后安全中止；锁 TTL 作为最终兜底。

## 正确性边界

- Bloom false 只有在 ready 与当前片都可信时才能拦截。
- MySQL 是事实来源；Bloom 故障只影响性能。
- 过期短码在历史片保留期间只可能带来额外回源，不会误伤合法短链。
- 若增量写失败，删除 ready 并等待定时完整重建。
- 多实例只有锁持有者可以删除当前片和发布 ready；锁过期的旧实例不能继续写批次或删除新锁。

## 实验结果

- 记录日期：2026-08-16
- 修复前基线提交：`90f2bbc08404fb18d6d999c7a6d86b864ca561cf`
- 修复版本：当前工作区，提交后补充最终 commit
- Redis：Redis 7，6 节点 Cluster
- 实验参数：每片写入 2000 个短码，保留 4 片
- 理论总体误判率：0.01
- 不存在样本数：20000
- 实际误判数：192
- 实际误判率：0.009600
- 当前片到最旧片 `MEMORY USAGE`：7256、3672、6232、7256 bytes
- 时间分片测试耗时：12.24 秒
- Maven 集群实验总耗时：14.075 秒
- 双实例交错重建：第一个实例写完 500 条后暂停，第二个实例抢锁失败；最终第 0 条和第 999 条均命中
- 锁安全实验：主动中止后另一实例可立即接管；锁 token 丢失后旧实例无法写批次或发布 ready

原始输出：

```text
samples=20000, falsePositives=192, actualRate=0.009600,
bitmapMemoryBytes=[7256, 3672, 6232, 7256]
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0,
Time elapsed: 12.24 s
BUILD SUCCESS
Total time: 14.075 s
```
````

---

# 六、实验验证

## 6.1 编译与纯单元测试

```bash
mvn -pl notification-infrastructure,notification-server -am \
  -Dtest=BloomFilterParametersTest,BloomSliceWindowTest,\
ShortLinkRedisKeysTest,RedisShortLinkProtectionFailOpenTest,\
ShortLinkBloomInitializerTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

验收：公式、UTC 边界、Hash Tag、启动预热和 Redis 故障 fail-open 全部通过。

## 6.2 启动真实 Redis Cluster

```bash
docker compose -f deploy/redis-cluster/compose.yml up -d
./deploy/redis-cluster/init-cluster.sh
```

确认：

```bash
docker exec -e REDISCLI_AUTH=notification123 \
  notification-redis-7001 \
  redis-cli -p 7001 CLUSTER INFO
```

必须看到 `cluster_state:ok` 和 16384 个正常槽。

## 6.3 运行真实时间分片集成测试

```bash
REDIS_CLUSTER_NODES='127.0.0.1:7001,127.0.0.1:7002,127.0.0.1:7003,127.0.0.1:7004,127.0.0.1:7005,127.0.0.1:7006' \
REDIS_CLUSTER_PASSWORD='notification123' \
mvn -pl notification-infrastructure -am \
  -Dtest=RedisTimeSlicedBloomFilterIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

必须验证：

- ready 删除期间 `mightContain` 返回 true；
- 当前片短码无 false negative；
- 历史片短码可查询；
- 第 5 片建立后第 1 片被删除；
- 输出实际误判数和实际误判率。

## 6.4 手工检查 Key 和槽位

先从 ready 读取当前片：

```bash
docker exec -e REDISCLI_AUTH=notification123 \
  notification-redis-7001 \
  redis-cli -c -p 7001 GET 'shortlink:{bloom:v3}:ready'
```

再检查 ready、registry 和当前 slice 的槽位，三个结果必须一致：

```bash
docker exec -e REDISCLI_AUTH=notification123 \
  notification-redis-7001 \
  redis-cli -p 7001 CLUSTER KEYSLOT 'shortlink:{bloom:v3}:ready'

docker exec -e REDISCLI_AUTH=notification123 \
  notification-redis-7001 \
  redis-cli -p 7001 CLUSTER KEYSLOT 'shortlink:{bloom:v3}:slices'
```

当前片 Key 使用上一步 ready 返回值拼成：

```text
shortlink:{bloom:v3}:slice:<ready返回值>
```

使用 `ZREVRANGE shortlink:{bloom:v3}:slices 0 -1 WITHSCORES` 查看活跃片，使用 `TTL` 查看 Bitmap 兜底过期时间。

## 6.5 快速观察自动轮换

本地实验临时覆盖配置：

```yaml
notification:
  shortlink:
    bloom:
      expected-insertions: 10000
      overall-false-positive-probability: 0.01
      slice-duration: PT1M
      retained-slice-count: 3
      rebuild-check-interval-ms: 5000
```

观察 4 个自然分钟。预期：

```text
第 1 分钟：片 A ready
第 2 分钟：片 B ready，A 为历史片
第 3 分钟：片 C ready，查询 A/B/C
第 4 分钟：片 D ready，A 被清理，只保留 B/C/D
```

实验结束必须恢复 `PT6H`，不要把一分钟配置提交为默认生产值。

## 6.6 验证 Redis 故障不误伤

1. 创建一条真实有效短链并确认能跳转。
2. 停止负责 Bloom slot 的 master。
3. 在故障检测窗口持续请求该短链。
4. 观察 Bloom 日志和 MySQL 回源。

验收不是“完全没有 Redis 报错”，而是：

```text
Redis 失败 → mightContain=true → MySQL 找到合法短链 → 跳转成功
```

恢复 Redis 后，删除 ready 或等待调度检查触发完整重建；ready 重新指向当前片后才能恢复否定判断。

## 6.7 验证过期短码退出

准备一条短有效期短链：

1. 在片 A 有效时完成重建；
2. 等短链过期；
3. 轮换到片 B，SQL 不再把它复制到当前片；
4. A 在历史窗口内时，该短码可能仍触发 MySQL 回源，这是允许的 false positive；
5. A 被清理后，该短码最终不再占用任何保留片。

注意：Bloom 的职责不是立即感知业务过期。`ShortLinkRedirectService` 仍以 MySQL 的 `status/expireAt` 做最终判断。

## 6.8 记录误判率与内存

至少保存以下原始数据：

```text
Git commit：
Redis 版本与节点数：
expectedInsertions：
retainedSliceCount：
理论总体误判率：
不存在样本数：
falsePositives：
actualRate：
每片 MEMORY USAGE：
测试总耗时：
```

使用：

```bash
docker exec -e REDISCLI_AUTH=notification123 \
  notification-redis-7001 \
  redis-cli -c -p 7001 MEMORY USAGE \
  'shortlink:{bloom:v3}:slice:<sliceId>'
```

理论值与实际值不必完全相同，但应在样本波动可解释的范围内。若实际值显著偏高，检查是否超过 `expectedInsertions`、是否查询了更多片、哈希是否均匀以及测试 Key 是否与已插入集合重叠。

## 6.9 完整回归

```bash
mvn test
```

必须确保短链创建、幂等、跳转、负缓存、Redis Cluster、点击统计、通知发送和 Worker 测试没有回归。

## 6.10 Day19 验收清单

- [ ] 参数来自样本量和总体误判率公式，不复制 2.16 亿配置；
- [ ] 只实现共享 Redis 时间片，没有本地 Bloom 和 Redis Stream；
- [ ] 当前片、历史片、轮换与旧片清理均有自动化验证；
- [ ] 启动时只加载 ACTIVE 且未过期短码；
- [ ] ready 最后写，重建期间 fail-open；
- [ ] Redis 故障期间合法短链仍能回源 MySQL；
- [ ] 所有 Bloom Key 使用同一 Hash Tag；
- [ ] 有理论和实际误判率原始数据；
- [ ] 有 `docs/bloom-lifecycle.md`；
- [ ] `mvn test` 全量通过。

---

# 七、面试追问

## 7.1 Bloom Filter 为什么不能简单删除？

因为不同元素可能共享 bit。清除某个元素对应的 bit 会同时破坏其他元素，产生 false negative。普通 Bitmap Bloom 只能安全置 1，不能按元素清零。

## 7.2 时间分片解决了什么？

它把元素级删除转换成整片删除。业务过期数据不会永久污染一个 Bitmap；当时间窗口过去后删除整个旧片，既不影响新片中的元素，又能限制累计元素量和误判率。

## 7.3 为什么本课程每次轮换都重建有效短码？

如果只按创建时间写片，一个有效期很长的短链会在创建片被删除后从 Bloom 消失，形成 false negative。轮换时复制仍有效短码，可以让长生命周期短链继续存在于当前片。

## 7.4 为什么查询 4 个片时不能给每片都配置 1%？

查询并集会放大误判率。近似总体误判率是 `1-(1-p)^r`。4 片各 1% 时总体约 3.94%，不是 1%。所以需要从总体目标反推单片目标。

## 7.5 ready 为什么必须最后写？

Bitmap 存在不等于内容完整。如果先写 ready，再逐步装数据，并发查询可能看到 ready 却遇到缺失 bit，把合法短码判成不存在。最后写 ready 相当于发布完整快照。

## 7.6 trusted 为什么既有 Redis 状态又有本机状态？

Redis ready 是多实例共享的完整性标志；本机 trusted 防止本机明知增量写失败后继续信任旧状态。其他实例完成重建后，本机可以通过共享 ready 恢复 trusted。

## 7.7 Redis 故障时为什么 Bloom 要 fail-open？

Bloom 是性能保护层，不是事实来源。fail-close 会因为缓存系统故障拒绝合法短链；fail-open 最多增加 MySQL 压力，正确性仍由 MySQL 保证。需要通过限流、负缓存和容量规划控制降级流量。

## 7.8 历史片中的过期短码会产生什么影响？

只会产生 false positive：请求继续查 MySQL，然后被 `expireAt` 拒绝。它不会让过期短链真的跳转，也不会误伤合法短链。历史片清理后这部分额外回源自然消失。

## 7.9 为什么还需要 TTL，已经有 ZSET 清理不够吗？

ZSET 是主动、及时的生命周期管理；TTL 是应用长期停机、调度未执行或清理异常时的被动兜底。两者结合防止孤儿 Bitmap 永久占用 Redis。

## 7.10 为什么不用 Redis KEYS 扫描旧片？

`KEYS` 会遍历节点 Key 空间，可能阻塞 Redis，而且 Cluster 还要逐节点执行。ZSET 显式登记片 ID，清理复杂度与片数量相关，范围明确且可测试。

## 7.11 为什么批量重建不能每个短码一次 Redis 请求？

10 万个短码就会产生 10 万次网络往返。将 500 个短码的 offset 合并进一次 Lua，能把往返降到约 200 次，同时限制单条脚本参数规模，避免一次脚本过大。

## 7.12 时间分片能彻底消除误判吗？

不能。它控制累计污染和生命周期，但 Bloom 的概率结构决定了误判仍然存在。最终存在性仍必须查询 MySQL，误判率必须通过样本实验持续观测。

## 7.13 多实例同时重建有什么风险，如何进一步演进？

如果只依靠清 ready 和幂等 SETBIT，实例 B 的 DEL 可能清除实例 A 已完成的批次，而实例 A 随后发布 ready，形成 false negative。最终版本使用带随机 token 和 TTL 的同槽分布式锁：抢锁和撤销 ready 原子执行，每批写入校验 token，发布 ready 时再次校验并原子释放锁。数据量继续增大后，还应加入锁续期、主键游标分页、重建耗时指标；若重建会操作无法由 Redis token 保护的外部资源，再进一步引入 fencing token。

## 7.14 这套方案最重要的正确性不变量是什么？

```text
只有在 ready 指向当前片且当前 Bitmap 存在时，Bloom 的 false 才能拦截请求；
其他任何状态都必须返回“可能存在”，交给 MySQL 裁决。
```

能把这句话、轮换数据流、参数公式和故障降级讲清楚，Day19 才算真正完成。
