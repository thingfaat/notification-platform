# Day 18：Redis Cluster 与槽位实验——完整教学版

> 本文只生成教学文档，不修改 `notification-platform` 或 `short_link` 项目源码。
>
> 真实代码基线：
>
> - 通知平台：`/Users/hingfaattam/workspace/learn_workspace/notification-platform`
> - 通知平台基线提交：`f95e6193ba934847e53380b29127dcf0dc2f2afe`
> - 知识星球短链：`/Users/hingfaattam/workspace/learn_workspace/short_link`
> - 学习计划：当前项目中的《多租户统一通知平台-补充学习计划》，以及 Day17 文档中保留的原 30 天计划进度说明
>
> 文中的“新增/修改代码”是学习 Day18 时需要手动加入通知平台的完整增量。代码基于上述真实目录、包名和现有实现编写，并用注释解释设计原因。

## 课程定位

补充学习计划对 Day18 的定义是：

```text
主题：Redis Cluster 与槽位实验

目标：
从“会使用 Redis”升级到理解 Redis Cluster 的键模型、路由和故障恢复。

动手任务：
1. 搭建最小 Redis Cluster；
2. 实现 CRC16 槽位计算并与真实 Redis 验证；
3. 为短链映射、负缓存和计数设计 Hash Tag；
4. 完成批量按槽分组；
5. 观察节点故障、MOVED、ASK 和客户端拓扑刷新。

验收：
给定任意 Key 能判断是否同槽；
跨槽批处理问题可以稳定复现；
节点切换后系统可以恢复，并且降级行为明确。

面试输出：
Redis Cluster 为什么需要 Hash Tag？
```

Day17 解决的是并发创建正确性，数据库是最终裁决者。Day18 不改变这个结论，也不把 Redis 升级成业务正确性的唯一依赖。

本日最终设计先写在前面：

```text
集群槽位总数：16384
槽位算法：CRC16(hashKey) & 0x3FFF

同一短码的键：
shortlink:{aZ8k2LmP}:redirect
shortlink:{aZ8k2LmP}:negative
shortlink:{aZ8k2LmP}:click:count   （只预留，不改变现有 MySQL 点击统计）

全局 Bloom 的键：
shortlink:{bloom:v2}:bitmap
shortlink:{bloom:v2}:ready

批量读取：
短码列表 → 本地缓存命中 → Redis Key → 按 slot 分组
→ 每个 slot 单独 MGET → 汇总结果

故障策略：
正缓存故障 → 回源 MySQL
负缓存故障 → 当作未命中
Bloom 故障 → fail-open，允许查询 MySQL
限流脚本故障 → 保持现有业务策略，不在 Day18 擅自改成 fail-open
```

---

# 一、原理

## 1.1 Redis Cluster 解决什么问题

单机 Redis 的主要限制不是语法，而是容量、吞吐和可用性都集中在一个进程上。Redis Cluster 把 Key 空间拆成 16384 个槽位，再把槽位分配给不同主节点。

```text
Key → CRC16 → slot 0...16383 → 负责该 slot 的主节点
```

因此客户端不是先选节点再发 Key，而是先根据 Key 算槽位，再找到负责这个槽位的节点。

Redis Cluster 主要提供：

1. 数据分片：不同槽位分布在多个主节点；
2. 水平扩容：可以把槽位迁移到新节点；
3. 主从故障转移：主节点故障后由副本晋升；
4. 客户端重定向：通过 MOVED、ASK 告知正确节点。

它没有自动解决：

- 多 Key 跨槽原子性；
- 热 Key；
- Big Key；
- 缓存与 MySQL 一致性；
- Redis 故障时业务应该放行还是拒绝。

## 1.2 为什么是 16384 个槽，而不是直接对节点数取模

如果直接执行：

```text
nodeIndex = hash(key) % nodeCount
```

节点数从 3 变成 4 时，大量 Key 的结果都会变化，迁移范围非常大。

Redis Cluster 增加槽位这一层：

```text
key → 固定 slot → slot 当前属于哪个节点
```

扩容时只迁移一部分槽位，Key 到槽位的关系不变。16384 既能提供足够细的迁移粒度，又不会让集群元数据过大。

## 1.3 Redis Cluster 的槽位算法

Redis 使用 CRC16-XMODEM 计算哈希，再保留低 14 位：

```text
slot = CRC16(hashKey) & 0x3FFF
```

`0x3FFF` 的二进制低 14 位全部为 1，因此结果范围是 `0—16383`。

这里不能换成 Java 的 `String.hashCode()`、MurmurHash 或 MD5，否则应用计算结果会与 Redis 不一致。

## 1.4 Hash Tag 的提取规则

当 Key 中存在第一对非空 `{...}` 时，Redis 只对大括号中的内容计算槽位。

```text
shortlink:{aZ8k2LmP}:redirect
shortlink:{aZ8k2LmP}:negative
```

两者真正参与 CRC16 的都是：

```text
aZ8k2LmP
```

因此它们必然同槽。

几个容易答错的边界：

```text
foo{}{bar}       第一对括号为空，按完整 Key 计算，不继续找 {bar}
foo{{bar}}zap    Hash Tag 是 {bar
foo{bar}{zap}    Hash Tag 是 bar，只看第一对有效括号
foo{bar          没有右括号，按完整 Key 计算
```

## 1.5 Hash Tag 不是越多越好

Hash Tag 的价值是让必须一起执行的 Key 同槽，例如：

- 同一个 Lua 脚本中的多个 `KEYS`；
- `MGET`、`MSET`；
- Redis 事务中的多个 Key；
- 同一个短码的映射和负缓存失效。

但如果所有 Key 都使用同一个 Tag：

```text
shortlink:{all}:redirect:a
shortlink:{all}:redirect:b
```

所有数据都会落到同一个槽位，集群退化成单节点热点。

本项目选择“一个 shortCode 一个 Tag”，因为短码全局唯一，且相关操作天然围绕 shortCode 展开。

## 1.6 CROSSSLOT 为什么出现

在 Cluster 中，多 Key 命令必须满足路由条件。以下两个 Key 的 Tag 不同：

```text
shortlink:{A}:redirect
shortlink:{B}:redirect
```

直接执行：

```redis
MGET shortlink:{A}:redirect shortlink:{B}:redirect
```

Redis 会返回：

```text
CROSSSLOT Keys in request don't hash to the same slot
```

正确做法不是给所有 Key 使用同一个全局 Tag，而是：

```text
先按 slot 分组 → 每组内部执行一次 MGET → 合并结果
```

这样既满足同槽约束，又保留数据分布能力。

> Redis Cluster 一共 16384 个 slot（哈希槽）。
> - 每个 key 通过 CRC16(key) % 16384 算出落到哪个槽。
> - 每个槽归属集群里某一个主节点。
> - 多 Key 原子命令（MGET/MSET 等）硬性约束：所有 key 必须落在同一个 slot，否则抛 CROSSSLOT。
> Hash Tag 机制：
> 如果 key 包含 {xxx}，Redis 不会拿整个 key 做 hash，只拿大括号里面的内容做 hash 计算槽位。
> ```plaintext
> shortlink:{A}:redirect → hash只算 A
> shortlink:{B}:redirect → hash只算 B
> ```
> {A} 和 {B} 哈希出来 slot 不一样，两个 key 落在不同集群节点。
> 你直接 MGET 一把传入这两个 key，命令要同时访问 2 个节点，Redis Cluster 拒绝执行，抛出 CROSSSLOT。

## 1.7 Lua 脚本同样受槽位约束

当前通知平台的 Bloom 检查脚本传入两个 Key：

```java
List.of(BLOOM_READY_KEY, BLOOM_BITMAP_KEY)
```

单机 Redis 可以执行，但 Cluster 要求所有 `KEYS` 同槽。当前 Key 是：

```text
shortlink:bloom:ready:v1
shortlink:bloom:codes:v1
```

它们没有 Hash Tag，通常会落入不同槽位。切换到 Cluster 后脚本会得到 `CROSSSLOT`，异常又会被现有 fail-open 捕获，于是业务仍能跳转，但 Bloom 将失去拦截效果。

Day18 必须把它们改成同一 Tag：

```text
shortlink:{bloom:v2}:ready
shortlink:{bloom:v2}:bitmap
```

## 1.8 MOVED 与 ASK 的区别

### MOVED

`MOVED` 表示槽位已经稳定归属于另一个节点：

```text
MOVED 12182 127.0.0.1:7002
```

客户端应该把请求发到新节点，并刷新槽位拓扑。它是“永久路由发生变化”的提示。

### ASK

`ASK` 常见于槽位迁移过程中，表示：

```text
这一次请求临时去目标节点；不要立即把整个槽位的永久归属改掉。
```

客户端要先向目标节点发送 `ASKING`，再执行原命令。

业务代码不应该手写 MOVED/ASK 解析。Spring Data Redis 默认使用 Lettuce，应该由客户端完成重定向；应用需要配置自适应和周期拓扑刷新。

## 1.9 拓扑刷新为什么仍然重要

仅仅配置多个 seed node 不代表客户端永远知道最新拓扑。扩容、缩容、主从切换后，旧连接中的槽位表可能过期。

本日配置两类刷新：

- adaptive：收到 MOVED、ASK、重连等信号时刷新；
- periodic：即使没有触发信号，也按周期校准。

seed node 只是发现集群的入口，不是所有请求都固定经过它。

## 1.10 故障时为什么仍要回到业务语义

Redis Cluster 提高可用性，但故障切换存在检测和晋升窗口。在这几秒内客户端仍可能超时。

通知平台已有正确边界：

```text
短链正缓存失败：回源 MySQL
负缓存失败：当作没有负缓存
Bloom 检查失败：返回“可能存在”，放行 MySQL
Bloom 写入失败：标记不可信
```

这叫 fail-open，牺牲性能保护正确性。

但限流不能自动照搬。限流 Redis 故障时是放行、拒绝还是使用本地保底，需要结合渠道成本和风险决定。Day18 只验证其 Key 已同槽，不改变现有策略。

## 1.11 读取知识星球项目后，哪些思想复用，哪些不照搬

真实参考代码：

- `ShardingStrategyService`：手写 CRC16 和 Hash Tag 提取；
- `ClusterAwareCacheService`：相关 Key 加 Tag，批量按槽分组；
- `RedissonConfig`：集群节点、读模式、扫描和超时；
- `redis/docker-compose-redis-cluster.yml`：三节点实验环境。

可以复用的问题意识：

- 应用侧能计算和验证 slot；
- 多 Key 操作必须先检查同槽；
- 批量操作应该按槽分组；
- 客户端需要感知拓扑变化。

不能直接复制的实现：

1. 配置中存在真实公网 IP 和密码，不能进入通知平台；
2. 三个主节点没有副本，不能验证自动故障转移；
3. 部分日志计算的是原始 shortCode 的槽，而不是实际 Redis Key 的槽；
4. `batchSize` 配置没有真正限制每批大小；
5. `CompletableFuture.runAsync` 使用公共线程池，缺少线程池和超时治理；
6. 按 `new ArrayList(...).indexOf(...)` 关联批量响应，复杂且脆弱；
7. 通知平台已有 Lettuce，没有必要为 Day18 再增加 Redisson；
8. Redis Stream 明确不属于 Day18，也不属于补充计划必做范围。

---

# 二、现有数据流

## 2.1 当前短链跳转链路

当前真实代码位于：

```text
notification-shortlink/.../ShortLinkRedirectService.java
notification-infrastructure/.../RedisShortLinkCache.java
notification-infrastructure/.../RedisShortLinkProtection.java
```

数据流是：

```text
GET /{shortCode}
  ↓
校验 8 位 Base62
  ↓
Caffeine 本地正缓存
  ├─ 命中 → 返回 originalUrl
  ↓ 未命中
Redis 正缓存 shortlink:redirect:<shortCode>
  ├─ 命中 → 回填 Caffeine → 返回
  ↓ 未命中或 Redis 异常
Redis 负缓存 shortlink:redirect:negative:<shortCode>
  ├─ 命中 → 404/410
  ↓ 未命中
Bloom ready + bitmap Lua 检查
  ├─ 明确不存在 → 写负缓存 → 404
  ├─ 可能存在 → 查询 MySQL
  └─ Redis 异常 → fail-open → 查询 MySQL
  ↓
跨租户查询 mapping → 恢复 tenantId → 查询 short_link
  ↓
校验状态和过期时间
  ↓
写 Redis 正缓存 + Caffeine，删除负缓存
```

## 2.2 当前单机 Redis 配置

`notification-server/src/main/resources/application.yml` 使用：

```yaml
spring:
  data:
    redis:
      host: 127.0.0.1
      port: 6379
```

`deploy/docker-compose.yml` 也只启动一个 Redis。当前所有 Key 都能执行，所以 CROSSSLOT 尚未暴露。

## 2.3 当前 Key 模型

| 功能 | 当前 Key | Cluster 风险 |
|---|---|---|
| 短链正缓存 | `shortlink:redirect:<code>` | 单 Key 可用，但相关键不保证同槽 |
| 负缓存 | `shortlink:redirect:negative:<code>` | 单 Key 可用，但无法与正缓存做同槽多 Key 操作 |
| Bloom Bitmap | `shortlink:bloom:codes:v1` | 与 ready 不同槽时 Lua CROSSSLOT |
| Bloom ready | `shortlink:bloom:ready:v1` | 与 bitmap 不同槽时 Lua CROSSSLOT |
| 点击统计 | MySQL + RocketMQ | Day18 不迁移到 Redis |

## 2.4 当前已有一个正确的 Hash Tag 示例

`RedisTokenBucketRateLimiter` 的 Lua 同时操作 bucket、quota、decision 三个 Key。它们都包含：

```text
{tenantId}
```

例如：

```text
notify:rate:{90001}:app:80001:channel:SMS
notify:quota:{90001}:app:80001:channel:SMS:daily
notify:rate:{90001}:decision:v2:event-1
```

前缀不同不会影响同槽，因为真正参与哈希的都是 `90001`。这部分直接复用，不修改。

## 2.5 当前没有批量短链缓存接口

`ShortLinkCache` 只有单条 `get/put/evict`。如果未来批量预热或批量解析直接执行跨槽 MGET，就会失败。

Day18 新增 `getAll`，但不强行增加新的业务 API。它用于完成集群批处理实验，并给未来批量预热提供正确基础。

## 2.6 当前故障降级链路

`RedisShortLinkCache` 和 `RedisShortLinkProtection` 都捕获 Redis 运行时异常：

- 正缓存异常返回 empty；
- 负缓存异常返回 empty；
- Bloom 异常返回 true；
- 写缓存异常只记录日志。

因此 Redis 节点切换期间，短链跳转会增加 MySQL 回源，但不会把合法短链误判为不存在。这是 Day18 故障实验要验证的业务结果。

---

# 三、本次需要改动的数据流

## 3.1 单条跳转的新数据流

```text
shortCode
  ↓
ShortLinkRedisKeys.redirect(shortCode)
  ↓
shortlink:{shortCode}:redirect
  ↓
Redis Cluster SlotHash
  ↓
负责该 slot 的主节点
  ↓
命中则返回；异常则保持现有 MySQL 回源
```

负缓存使用相同 `{shortCode}`，所以同一短码的正、负缓存天然同槽。

## 3.2 Bloom Lua 的新数据流

```text
CHECK_BITS_SCRIPT
  ├─ KEYS[1] = shortlink:{bloom:v2}:ready
  └─ KEYS[2] = shortlink:{bloom:v2}:bitmap
                 ↓
           两个 Key 的 Hash Tag 都是 bloom:v2
                 ↓
              同一个 slot
                 ↓
        Lua 可以在 Cluster 中原子执行
```

使用 v2 而不是复用旧 Key，是为了避免旧单机数据被误认为已经完成新版本重建。

## 3.3 批量读取的新数据流

```text
输入 [codeA, codeB, codeC]
  ↓
去重、过滤空值
  ↓
先读取 Caffeine
  ↓
未命中的 code 构建真实 Redis Key
  ↓
RedisClusterSlot.slot(key)
  ↓
Map<slot, List<code>>
  ↓
每个 slot 单独执行 MGET
  ↓
反序列化、回填 Caffeine、合并结果
```

每个 slot 失败只丢失这一组缓存结果，调用方仍可回源数据库。

## 3.4 MOVED/ASK 与拓扑刷新数据流

```text
客户端根据旧拓扑访问节点 A
  ↓
节点 A 返回 MOVED 或 ASK
  ↓
Lettuce 自动重定向当前请求
  ↓
adaptive refresh 更新拓扑
  ↓
后续请求直接到新节点
```

## 3.5 节点故障时的数据流

```text
主节点停止
  ↓
故障检测窗口内 Redis 可能超时
  ↓
短链缓存/负缓存/Bloom 捕获异常并 fail-open
  ↓
请求回源 MySQL
  ↓
副本晋升 + Lettuce 刷新拓扑
  ↓
Redis 命中能力逐步恢复
```

注意：如果 Bloom 写入在故障期间失败，本机 `bloomTrusted` 会变成 false。正确性仍然恢复，但 Bloom 性能保护需要重新执行完整重建；自动重建生命周期属于 Day19。

---

# 四、文件位置（复用 / 新增 / 修改）

## 4.1 复用，不修改

| 文件 | 原因 |
|---|---|
| `ShortLinkRedirectService.java` | 已具备缓存失败回源与 Bloom fail-open |
| `ShortLinkProtection.java` | 抽象语义不因单机/集群变化 |
| `RedisTokenBucketRateLimiter.java` | 三个 Lua Key 已使用相同 `{tenantId}` |
| `ShortLinkBloomInitializer.java` | 启动时仍负责完整 Bloom 重建 |
| `deploy/docker-compose.yml` | 保留默认单机开发环境，不强制所有开发都使用 Cluster |

## 4.2 新增

| 文件 | 作用 |
|---|---|
| `notification-infrastructure/.../redis/RedisClusterSlot.java` | Redis 官方 CRC16 + Hash Tag 槽位计算 |
| `notification-infrastructure/.../shortlink/ShortLinkRedisKeys.java` | 集中定义 Cluster-safe Key |
| `notification-infrastructure/.../redis/RedisClusterSlotTest.java` | 验证已知槽位与边界规则 |
| `notification-infrastructure/.../shortlink/ShortLinkRedisKeysTest.java` | 验证相关 Key 同槽、不同短码可分散 |
| `notification-infrastructure/.../shortlink/RedisShortLinkClusterIntegrationTest.java` | 连接真实 Cluster 验证同槽 MGET 与跨槽分组 |
| `notification-server/src/main/resources/application-redis-cluster.yml` | Cluster 专用 profile 与拓扑刷新 |
| `deploy/redis-cluster/compose.yml` | 3 主 3 从实验集群 |
| `deploy/redis-cluster/init-cluster.sh` | 可重复创建集群 |
| `docs/redis-cluster-key-design.md` | Day18 设计结论和迁移策略 |

## 4.3 修改

| 文件 | 修改内容 |
|---|---|
| `ShortLinkCache.java` | 增加批量读取契约 |
| `RedisShortLinkCache.java` | 使用 Hash Tag Key，按槽 MGET |
| `RedisShortLinkProtection.java` | 负缓存使用短码 Tag；Bloom 两个 Lua Key 使用全局同 Tag |

## 4.4 明确不改

- 不把点击统计从 MySQL 迁到 Redis；
- 不实现时间分片 Bloom，那是 Day19；
- 不引入 Redisson；
- 不引入 Redis Stream；
- 不改变短链创建的数据库唯一约束；
- 不把 Redis 变成短链正确性的最终边界。

---

# 五、基于现有代码的完整增量代码

## 5.1 新增 RedisClusterSlot

文件：

```text
notification-infrastructure/src/main/java/com/tam/notification/redis/RedisClusterSlot.java
```

```java
package com.tam.notification.redis;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Redis Cluster 槽位计算工具。
 *
 * 算法必须与 Redis 保持一致：
 * 1. 提取第一对非空大括号中的 Hash Tag；
 * 2. 对 UTF-8 字节执行 CRC16-XMODEM；
 * 3. 保留低 14 位，得到 0—16383。
 */
public final class RedisClusterSlot {

    public static final int SLOT_COUNT = 16_384;
    private static final int SLOT_MASK = SLOT_COUNT - 1;

    private RedisClusterSlot() {
    }

    /** 计算一个真实 Redis Key 所属的槽位。 */
    public static int slot(String key) {
        Objects.requireNonNull(key, "redis key不能为空");

        byte[] bytes = hashKey(key).getBytes(StandardCharsets.UTF_8);
        return crc16(bytes) & SLOT_MASK;
    }

    /**
     * 按槽位分组，并保留输入顺序。
     * 每一组可以安全执行 MGET/MSET 等同槽多 Key 命令。
     */
    public static Map<Integer, List<String>> groupBySlot(
            Collection<String> keys
    ) {
        Objects.requireNonNull(keys, "redis keys不能为空");

        return keys.stream()
                .map(key -> Objects.requireNonNull(
                        key,
                        "redis key不能为空"
                ))
                .collect(Collectors.groupingBy(
                        RedisClusterSlot::slot,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
    }

    /**
     * Redis 只识别第一对大括号。
     * 第一对括号为空时必须按完整 Key 哈希，不能继续寻找后面的括号。
     */
    static String hashKey(String key) {
        int open = key.indexOf('{');
        if (open < 0) {
            return key;
        }

        int close = key.indexOf('}', open + 1);
        if (close > open + 1) {
            return key.substring(open + 1, close);
        }

        return key;
    }

    /** CRC16-XMODEM，多项式 0x1021，初始值 0。 */
    private static int crc16(byte[] bytes) {
        int crc = 0;

        for (byte value : bytes) {
            crc ^= (value & 0xFF) << 8;

            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 0x8000) != 0) {
                    crc = (crc << 1) ^ 0x1021;
                } else {
                    crc <<= 1;
                }

                crc &= 0xFFFF;
            }
        }

        return crc;
    }
}
```

这里保留应用侧算法不是为了替代客户端路由，而是为了：

- 单元测试 Key 设计；
- 批量按槽分组；
- 日志和故障实验能够解释路由结果。

## 5.2 新增 ShortLinkRedisKeys

文件：

```text
notification-infrastructure/src/main/java/com/tam/notification/shortlink/ShortLinkRedisKeys.java
```

```java
package com.tam.notification.shortlink;

/**
 * 通知短链的 Redis Key 统一入口。
 *
 * shortCode 当前是全局唯一 8 位 Base62，因此可以直接作为 Hash Tag。
 * 禁止调用方自己拼接 Key，避免同一业务对象意外散落到不同槽位。
 */
public final class ShortLinkRedisKeys {

    private static final String BLOOM_TAG = "bloom:v2";

    private ShortLinkRedisKeys() {
    }

    public static String redirect(String shortCode) {
        return "shortlink:{" + requireShortCode(shortCode) + "}:redirect";
    }

    public static String negative(String shortCode) {
        return "shortlink:{" + requireShortCode(shortCode) + "}:negative";
    }

    /**
     * Day18 只验证 Key 设计，不改变现有 RocketMQ + MySQL 点击统计链路。
     */
    public static String clickCount(String shortCode) {
        return "shortlink:{" + requireShortCode(shortCode) + "}:click:count";
    }

    public static String bloomBitmap() {
        return "shortlink:{" + BLOOM_TAG + "}:bitmap";
    }

    public static String bloomReady() {
        return "shortlink:{" + BLOOM_TAG + "}:ready";
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

## 5.3 修改 ShortLinkCache

文件：

```text
notification-core/src/main/java/com/tam/notification/domain/shortlink/ShortLinkCache.java
```

完整替换为：

```java
package com.tam.notification.domain.shortlink;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

public interface ShortLinkCache {

    Optional<ShortLinkCacheEntry> get(String shortCode);

    /**
     * 批量读取缓存。
     *
     * 返回结果只包含命中的短码；未命中或缓存故障由调用方回源数据库。
     */
    Map<String, ShortLinkCacheEntry> getAll(
            Collection<String> shortCodes
    );

    void put(
            String shortCode,
            ShortLinkCacheEntry entry,
            Duration ttl
    );

    void evict(String shortCode);
}
```

## 5.4 修改 RedisShortLinkCache

文件：

```text
notification-infrastructure/src/main/java/com/tam/notification/shortlink/RedisShortLinkCache.java
```

完整替换为：

```java
package com.tam.notification.shortlink;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tam.notification.domain.shortlink.ShortLinkCache;
import com.tam.notification.domain.shortlink.ShortLinkCacheEntry;
import com.tam.notification.redis.RedisClusterSlot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Redis Cluster 感知的短链正缓存实现。 */
@Slf4j
@Service
public class RedisShortLinkCache implements ShortLinkCache {

    /** 限制单次响应体和节点占用时间，避免一个超大 MGET 形成新的尖峰。 */
    private static final int MAX_MGET_KEYS = 100;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Cache<String, ShortLinkCacheEntry> localCache;

    public RedisShortLinkCache(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${notification.shortlink.hot-cache.max-size:10000}")
            long maximumSize,
            @Value("${notification.shortlink.hot-cache.ttl:PT1M}")
            Duration localTtl
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.localCache = Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfterWrite(localTtl)
                .build();
    }

    @Override
    public Optional<ShortLinkCacheEntry> get(String shortCode) {
        ShortLinkCacheEntry local = localCache.getIfPresent(shortCode);
        if (local != null) {
            return Optional.of(local);
        }

        String redisKey = ShortLinkRedisKeys.redirect(shortCode);

        try {
            String payload = redisTemplate.opsForValue().get(redisKey);
            if (payload == null) {
                return Optional.empty();
            }

            ShortLinkCacheEntry entry = deserialize(redisKey, payload);
            localCache.put(shortCode, entry);
            return Optional.of(entry);
        } catch (JsonProcessingException exception) {
            log.warn("short link cache payload is invalid, key={}", redisKey, exception);
            evict(shortCode);
            return Optional.empty();
        } catch (RuntimeException exception) {
            // Redis 故障只造成缓存未命中，调用方继续回源 MySQL。
            log.warn("read short link cache failed, key={}", redisKey, exception);
            return Optional.empty();
        }
    }

    @Override
    public Map<String, ShortLinkCacheEntry> getAll(
            Collection<String> shortCodes
    ) {
        if (shortCodes == null || shortCodes.isEmpty()) {
            return Map.of();
        }

        /*
         * LinkedHashSet 同时完成去重和输入顺序保留。
         * 空值不是合法短码，直接跳过，避免一个坏参数拖垮整批。
         */
        LinkedHashSet<String> distinctCodes = new LinkedHashSet<>();
        for (String shortCode : shortCodes) {
            if (shortCode != null && !shortCode.isBlank()) {
                distinctCodes.add(shortCode);
            }
        }

        Map<String, ShortLinkCacheEntry> result = new LinkedHashMap<>();
        List<String> missedCodes = new ArrayList<>();

        for (String shortCode : distinctCodes) {
            ShortLinkCacheEntry local = localCache.getIfPresent(shortCode);
            if (local == null) {
                missedCodes.add(shortCode);
            } else {
                result.put(shortCode, local);
            }
        }

        /*
         * 必须按“真实 Redis Key”计算槽位，不能只对 shortCode 计算。
         * 当前 Hash Tag 使结果一致，但使用真实 Key 能防止未来改前缀后日志失真。
         */
        Map<Integer, List<String>> codesBySlot = new LinkedHashMap<>();
        for (String shortCode : missedCodes) {
            String redisKey = ShortLinkRedisKeys.redirect(shortCode);
            int slot = RedisClusterSlot.slot(redisKey);
            codesBySlot.computeIfAbsent(slot, ignored -> new ArrayList<>())
                    .add(shortCode);
        }

        for (Map.Entry<Integer, List<String>> group : codesBySlot.entrySet()) {
            List<String> codesInSlot = group.getValue();

            /*
             * 同槽只是命令能够执行的前提，不代表一批可以无限大。
             * 再按 100 个一组切片，控制单条命令的响应大小和执行时间。
             */
            for (int from = 0;
                 from < codesInSlot.size();
                 from += MAX_MGET_KEYS) {
                int to = Math.min(
                        from + MAX_MGET_KEYS,
                        codesInSlot.size()
                );

                readOneSlot(
                        group.getKey(),
                        codesInSlot.subList(from, to),
                        result
                );
            }
        }

        return result;
    }

    /** 一个 MGET 只处理一个槽位，避免 CROSSSLOT。 */
    private void readOneSlot(
            int slot,
            List<String> shortCodes,
            Map<String, ShortLinkCacheEntry> result
    ) {
        List<String> redisKeys = shortCodes.stream()
                .map(ShortLinkRedisKeys::redirect)
                .toList();

        try {
            List<String> payloads = redisTemplate
                    .opsForValue()
                    .multiGet(redisKeys);

            if (payloads == null) {
                return;
            }

            for (int index = 0; index < shortCodes.size(); index++) {
                String payload = payloads.get(index);
                if (payload == null) {
                    continue;
                }

                String shortCode = shortCodes.get(index);
                String redisKey = redisKeys.get(index);

                try {
                    ShortLinkCacheEntry entry = deserialize(redisKey, payload);
                    localCache.put(shortCode, entry);
                    result.put(shortCode, entry);
                } catch (JsonProcessingException exception) {
                    log.warn(
                            "short link cache payload is invalid, key={}",
                            redisKey,
                            exception
                    );
                    evict(shortCode);
                }
            }
        } catch (RuntimeException exception) {
            /*
             * 单个槽位失败不影响其他槽位；未返回的部分由上层回源数据库。
             * 不在这里并发访问所有槽，避免把公共线程池和集群同时打满。
             */
            log.warn(
                    "batch read short link cache failed, slot={}, size={}",
                    slot,
                    shortCodes.size(),
                    exception
            );
        }
    }

    @Override
    public void put(
            String shortCode,
            ShortLinkCacheEntry entry,
            Duration ttl
    ) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return;
        }

        // Redis 异常时，本机热点缓存仍可提供短时间降级能力。
        localCache.put(shortCode, entry);

        String redisKey = ShortLinkRedisKeys.redirect(shortCode);

        try {
            String payload = objectMapper.writeValueAsString(entry);
            redisTemplate.opsForValue().set(redisKey, payload, ttl);
        } catch (JsonProcessingException exception) {
            log.warn("short link cache serialization failed, key={}", redisKey, exception);
        } catch (RuntimeException exception) {
            log.warn("write short link cache failed, key={}", redisKey, exception);
        }
    }

    @Override
    public void evict(String shortCode) {
        localCache.invalidate(shortCode);

        String redisKey = ShortLinkRedisKeys.redirect(shortCode);

        try {
            redisTemplate.delete(redisKey);
        } catch (RuntimeException exception) {
            log.warn("evict short link cache failed, key={}", redisKey, exception);
        }
    }

    private ShortLinkCacheEntry deserialize(
            String redisKey,
            String payload
    ) throws JsonProcessingException {
        return objectMapper.readValue(payload, ShortLinkCacheEntry.class);
    }
}
```

说明：`deserialize` 保留 `redisKey` 参数是为了以后加入指标或带 Key 的异常；如果 IDE 提示暂未使用，也可以先移除该参数。

## 5.5 修改 RedisShortLinkProtection

该类主体逻辑不变，只替换所有 Key 构造。下面是完整增量补丁：

```diff
 public class RedisShortLinkProtection implements ShortLinkProtection {
-    private static final String NEGATIVE_KEY_PREFIX =
-            "shortlink:redirect:negative:";
-    private static final String BLOOM_BITMAP_KEY =
-            "shortlink:bloom:codes:v1";
-    private static final String BLOOM_READY_KEY =
-            "shortlink:bloom:ready:v1";
+    /*
+     * Key 统一由 ShortLinkRedisKeys 构造。
+     * Bloom ready 与 bitmap 必须同槽，否则双 Key Lua 在 Cluster 中会 CROSSSLOT。
+     */
+    private static final String BLOOM_BITMAP_KEY =
+            ShortLinkRedisKeys.bloomBitmap();
+    private static final String BLOOM_READY_KEY =
+            ShortLinkRedisKeys.bloomReady();
@@
     private String negativeKey(String shortCode) {
-        return NEGATIVE_KEY_PREFIX + shortCode;
+        return ShortLinkRedisKeys.negative(shortCode);
     }
 }
```

不要改 Lua 脚本里的 `KEYS[1]`、`KEYS[2]` 顺序。它仍然先检查 ready，再读取 bitmap；变化的是两个 Key 现在保证同槽。

## 5.6 新增 Redis Cluster profile

文件：

```text
notification-server/src/main/resources/application-redis-cluster.yml
```

```yaml
spring:
  config:
    activate:
      on-profile: redis-cluster

  data:
    redis:
      # 节点只用于初始拓扑发现，客户端会根据 slot 路由到真实节点。
      cluster:
        nodes: ${REDIS_CLUSTER_NODES:127.0.0.1:7001,127.0.0.1:7002,127.0.0.1:7003,127.0.0.1:7004,127.0.0.1:7005,127.0.0.1:7006}
        max-redirects: 5

      password: ${REDIS_CLUSTER_PASSWORD:notification123}
      connect-timeout: 2s
      timeout: 2s

      lettuce:
        cluster:
          refresh:
            # MOVED、ASK、重连等事件触发拓扑刷新。
            adaptive: true
            # 周期刷新用于兜底长期没有触发事件的拓扑变化。
            period: 5s
            dynamic-refresh-sources: true
```

默认 `application.yml` 的单机配置继续保留。只有显式启用 `redis-cluster` profile 才连接实验集群。

## 5.7 新增 3 主 3 从实验环境

文件：

```text
deploy/redis-cluster/compose.yml
```

```yaml
name: notification-redis-cluster

x-redis-common: &redis-common
  image: redis:7
  restart: unless-stopped
  network_mode: host

services:
  redis-7001:
    <<: *redis-common
    container_name: notification-redis-7001
    command: ["redis-server", "--port", "7001", "--cluster-enabled", "yes", "--cluster-config-file", "nodes.conf", "--cluster-node-timeout", "5000", "--appendonly", "yes", "--requirepass", "notification123", "--masterauth", "notification123"]
    volumes: ["redis-7001-data:/data"]

  redis-7002:
    <<: *redis-common
    container_name: notification-redis-7002
    command: ["redis-server", "--port", "7002", "--cluster-enabled", "yes", "--cluster-config-file", "nodes.conf", "--cluster-node-timeout", "5000", "--appendonly", "yes", "--requirepass", "notification123", "--masterauth", "notification123"]
    volumes: ["redis-7002-data:/data"]

  redis-7003:
    <<: *redis-common
    container_name: notification-redis-7003
    command: ["redis-server", "--port", "7003", "--cluster-enabled", "yes", "--cluster-config-file", "nodes.conf", "--cluster-node-timeout", "5000", "--appendonly", "yes", "--requirepass", "notification123", "--masterauth", "notification123"]
    volumes: ["redis-7003-data:/data"]

  redis-7004:
    <<: *redis-common
    container_name: notification-redis-7004
    command: ["redis-server", "--port", "7004", "--cluster-enabled", "yes", "--cluster-config-file", "nodes.conf", "--cluster-node-timeout", "5000", "--appendonly", "yes", "--requirepass", "notification123", "--masterauth", "notification123"]
    volumes: ["redis-7004-data:/data"]

  redis-7005:
    <<: *redis-common
    container_name: notification-redis-7005
    command: ["redis-server", "--port", "7005", "--cluster-enabled", "yes", "--cluster-config-file", "nodes.conf", "--cluster-node-timeout", "5000", "--appendonly", "yes", "--requirepass", "notification123", "--masterauth", "notification123"]
    volumes: ["redis-7005-data:/data"]

  redis-7006:
    <<: *redis-common
    container_name: notification-redis-7006
    command: ["redis-server", "--port", "7006", "--cluster-enabled", "yes", "--cluster-config-file", "nodes.conf", "--cluster-node-timeout", "5000", "--appendonly", "yes", "--requirepass", "notification123", "--masterauth", "notification123"]
    volumes: ["redis-7006-data:/data"]

volumes:
  redis-7001-data:
  redis-7002-data:
  redis-7003-data:
  redis-7004-data:
  redis-7005-data:
  redis-7006-data:
```

为什么使用 6 个节点：三个主节点是 Cluster 的最小分片规模；再增加三个副本，才能验证主节点停止后的自动晋升。

`network_mode: host` 是为了让节点公布的 `127.0.0.1:700x` 同时能被宿主机应用和其他节点访问。macOS Docker Desktop 需要先在设置中启用 host networking；如果环境不支持，应使用可路由的宿主机 IP 配置 `cluster-announce-ip`，不要照搬知识星球项目中的公网 IP。

## 5.8 新增集群初始化脚本

文件：

```text
deploy/redis-cluster/init-cluster.sh
```

```bash
#!/usr/bin/env bash
set -euo pipefail
#
password="${REDIS_CLUSTER_PASSWORD:-notification123}"
ports=(7001 7002 7003 7004 7005 7006)

for port in "${ports[@]}"; do
  container="notification-redis-${port}"

  # 不把密码放在 redis-cli 参数中，避免命令行警告和进程列表泄漏。
  until docker exec \
      -e REDISCLI_AUTH="${password}" \
      "${container}" \
      redis-cli -p "${port}" PING >/dev/null; do
    echo "waiting for redis ${port}"
    sleep 1
  done
done

# 已经初始化成功时直接退出，使脚本可重复执行。
if docker exec \
    -e REDISCLI_AUTH="${password}" \
    notification-redis-7001 \
    redis-cli -p 7001 CLUSTER INFO \
    | grep -q 'cluster_state:ok'; then
  echo "redis cluster is already ready"
  exit 0
fi

docker exec \
  -e REDISCLI_AUTH="${password}" \
  notification-redis-7001 \
  redis-cli --cluster create \
  127.0.0.1:7001 \
  127.0.0.1:7002 \
  127.0.0.1:7003 \
  127.0.0.1:7004 \
  127.0.0.1:7005 \
  127.0.0.1:7006 \
  --cluster-replicas 1 \
  --cluster-yes

docker exec \
  -e REDISCLI_AUTH="${password}" \
  notification-redis-7001 \
  redis-cli -p 7001 CLUSTER INFO
```

首次保存后执行：

```bash
chmod +x deploy/redis-cluster/init-cluster.sh
```

## 5.9 新增 RedisClusterSlotTest

文件：

```text
notification-infrastructure/src/test/java/com/tam/notification/redis/RedisClusterSlotTest.java
```

```java
package com.tam.notification.redis;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RedisClusterSlotTest {

    @Test
    void shouldMatchRedisKnownSlots() {
        // 这两个结果可以使用 redis-cli CLUSTER KEYSLOT 再次核对。
        assertEquals(12_182, RedisClusterSlot.slot("foo"));
        assertEquals(5_061, RedisClusterSlot.slot("bar"));
    }

    @Test
    void shouldUseFirstNonEmptyHashTag() {
        assertEquals(
                RedisClusterSlot.slot("user1000"),
                RedisClusterSlot.slot("{user1000}.following")
        );
        assertEquals(
                RedisClusterSlot.slot("{user1000}.following"),
                RedisClusterSlot.slot("{user1000}.followers")
        );
    }

    @Test
    void emptyFirstBracesShouldHashWholeKey() {
        assertEquals(
                "foo{}{bar}",
                RedisClusterSlot.hashKey("foo{}{bar}")
        );
        assertNotEquals(
                RedisClusterSlot.slot("bar"),
                RedisClusterSlot.slot("foo{}{bar}")
        );
    }

    @Test
    void shouldGroupKeysByActualSlot() {
        List<String> keys = List.of(
                "shortlink:{A}:redirect",
                "shortlink:{A}:negative",
                "shortlink:{B}:redirect"
        );

        Map<Integer, List<String>> groups =
                RedisClusterSlot.groupBySlot(keys);

        assertEquals(2, groups.size());
        assertEquals(3, groups.values().stream().mapToInt(List::size).sum());

        groups.forEach((slot, group) -> group.forEach(
                key -> assertEquals(slot, RedisClusterSlot.slot(key))
        ));
    }
}
```

## 5.10 新增 ShortLinkRedisKeysTest

文件：

```text
notification-infrastructure/src/test/java/com/tam/notification/shortlink/ShortLinkRedisKeysTest.java
```

```java
package com.tam.notification.shortlink;

import com.tam.notification.redis.RedisClusterSlot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ShortLinkRedisKeysTest {

    @Test
    void sameShortCodeKeysShouldUseSameSlot() {
        String shortCode = "aZ8k2LmP";

        int redirectSlot = RedisClusterSlot.slot(
                ShortLinkRedisKeys.redirect(shortCode)
        );

        assertEquals(
                redirectSlot,
                RedisClusterSlot.slot(ShortLinkRedisKeys.negative(shortCode))
        );
        assertEquals(
                redirectSlot,
                RedisClusterSlot.slot(ShortLinkRedisKeys.clickCount(shortCode))
        );
    }

    @Test
    void bloomKeysShouldUseSameGlobalSlot() {
        assertEquals(
                RedisClusterSlot.slot(ShortLinkRedisKeys.bloomBitmap()),
                RedisClusterSlot.slot(ShortLinkRedisKeys.bloomReady())
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
        assertThrows(
                IllegalArgumentException.class,
                () -> ShortLinkRedisKeys.redirect("bad{code}")
        );
    }
}
```

## 5.11 新增真实 Cluster 集成测试

文件：

```text
notification-infrastructure/src/test/java/com/tam/notification/shortlink/RedisShortLinkClusterIntegrationTest.java
```

该测试只有设置 `REDIS_CLUSTER_NODES` 时才执行，避免普通 `mvn test` 强制依赖手工集群。

```java
package com.tam.notification.shortlink;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tam.notification.domain.shortlink.ShortLinkCacheEntry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnabledIfEnvironmentVariable(
        named = "REDIS_CLUSTER_NODES",
        matches = ".+"
)
class RedisShortLinkClusterIntegrationTest {

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static ObjectMapper objectMapper;

    @BeforeAll
    static void setUp() {
        List<String> nodes = List.of(
                System.getenv("REDIS_CLUSTER_NODES").split(",")
        );

        RedisClusterConfiguration configuration =
                new RedisClusterConfiguration(nodes);

        String password = System.getenv().getOrDefault(
                "REDIS_CLUSTER_PASSWORD",
                "notification123"
        );
        configuration.setPassword(RedisPassword.of(password));
        configuration.setMaxRedirects(5);

        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());
    }

    @AfterAll
    static void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void sameTagShouldSupportMultiGet() {
        String shortCode = "D18SLOT1";
        String redirectKey = ShortLinkRedisKeys.redirect(shortCode);
        String negativeKey = ShortLinkRedisKeys.negative(shortCode);

        try {
            redisTemplate.opsForValue().set(redirectKey, "redirect-value");
            redisTemplate.opsForValue().set(negativeKey, "NOT_FOUND");

            assertEquals(
                    List.of("redirect-value", "NOT_FOUND"),
                    redisTemplate.opsForValue().multiGet(
                            List.of(redirectKey, negativeKey)
                    )
            );
        } finally {
            redisTemplate.delete(List.of(redirectKey, negativeKey));
        }
    }

    @Test
    void batchReaderShouldMergeMultipleSlots() throws Exception {
        String firstCode = "D18BAT01";
        String secondCode = "D18BAT02";

        ShortLinkCacheEntry first = entry(101L, "https://example.com/1");
        ShortLinkCacheEntry second = entry(102L, "https://example.com/2");

        String firstKey = ShortLinkRedisKeys.redirect(firstCode);
        String secondKey = ShortLinkRedisKeys.redirect(secondCode);

        try {
            redisTemplate.opsForValue().set(
                    firstKey,
                    objectMapper.writeValueAsString(first)
            );
            redisTemplate.opsForValue().set(
                    secondKey,
                    objectMapper.writeValueAsString(second)
            );

            RedisShortLinkCache cache = new RedisShortLinkCache(
                    redisTemplate,
                    objectMapper,
                    100,
                    Duration.ofMinutes(1)
            );

            Map<String, ShortLinkCacheEntry> result = cache.getAll(
                    List.of(firstCode, secondCode)
            );

            assertEquals(first, result.get(firstCode));
            assertEquals(second, result.get(secondCode));
        } finally {
            redisTemplate.delete(List.of(firstKey, secondKey));
        }
    }

    private ShortLinkCacheEntry entry(Long id, String url) {
        return new ShortLinkCacheEntry(
                10001L,
                id,
                url,
                LocalDateTime.now().plusMinutes(10)
        );
    }
}
```

注意：如果 `ShortLinkCacheEntry` 所在版本没有实现基于字段的 `equals`，最后两个断言应分别比较 `shortLinkId`、`originalUrl` 和 `expireAt`。当前它是 record，可以直接比较。

## 5.12 新增 redis-cluster-key-design.md

文件：

```text
docs/redis-cluster-key-design.md
```

完整内容：

```markdown
# Redis Cluster Key Design

- 状态：Accepted
- 适用范围：通知平台 Redis Key 与多 Key 操作
- 决策日期：Day18

## 决策

1. Redis Cluster 使用 16384 个 slot，应用侧使用 Redis 官方 CRC16 算法验证路由。
2. 同一短码的正缓存、负缓存和预留计数使用 `{shortCode}` Hash Tag。
3. Bloom ready 与 bitmap 使用 `{bloom:v2}`，保证 Lua 的所有 KEYS 同槽。
4. 批量多 Key 操作必须先按真实 Redis Key 的 slot 分组。
5. 不使用全局 `{shortlink}` Tag，避免所有短链集中到一个槽位。
6. 业务代码不处理 MOVED/ASK，由 Lettuce 路由并刷新拓扑。
7. Redis 故障时，短链缓存和 Bloom 保持 fail-open，MySQL 仍是事实来源。

## Key 清单

| 业务 | Key | Hash Tag |
|---|---|---|
| 正缓存 | `shortlink:{code}:redirect` | `code` |
| 负缓存 | `shortlink:{code}:negative` | `code` |
| 点击计数预留 | `shortlink:{code}:click:count` | `code` |
| Bloom Bitmap | `shortlink:{bloom:v2}:bitmap` | `bloom:v2` |
| Bloom ready | `shortlink:{bloom:v2}:ready` | `bloom:v2` |
| 限流 bucket | `notify:rate:{tenant}:...` | `tenant` |
| 每日 quota | `notify:quota:{tenant}:...` | `tenant` |
| 限流 decision | `notify:rate:{tenant}:decision:...` | `tenant` |

## 兼容与迁移

- 旧正缓存最长 30 分钟，切换后自然过期；新版本首次访问会回源并预热。
- 旧负缓存最长约 2 分 30 秒，切换后自然过期。
- Bloom 使用 v2 新 Key，启动时必须完整重建后才写 ready。
- v1 Bloom 验证稳定后再人工删除，禁止先删旧数据再发布新代码。
- Key 改名只影响缓存命中率，不允许影响 MySQL 正确性。

## 风险

- 热门 shortCode 仍会形成热 Key/热 slot，Hash Tag 不能解决业务热点。
- 全局 Bloom Bitmap 天然集中在一个 slot；Day19 将讨论时间分片和生命周期。
- 按槽 MGET 减少命令数，但批量大小仍应设置上限，避免一次响应过大。
```

---

# 六、实验验证

## 6.1 实验前准备

先确认 Docker Desktop 已启用 host networking，然后启动集群：

```bash
docker compose -f deploy/redis-cluster/compose.yml up -d
./deploy/redis-cluster/init-cluster.sh
```

查看状态：

```bash
docker exec \
  -e REDISCLI_AUTH=notification123 \
  notification-redis-7001 \
  redis-cli -p 7001 CLUSTER INFO

docker exec \
  -e REDISCLI_AUTH=notification123 \
  notification-redis-7001 \
  redis-cli -p 7001 CLUSTER NODES
```

验收状态：

```text
cluster_state:ok
cluster_slots_assigned:16384
cluster_slots_ok:16384
3 个 master
3 个 slave
```

如果需要完全重建实验集群，可以执行 `docker compose ... down -v`。该命令会删除 Day18 Redis Cluster 的全部实验数据，只能针对这个明确的 compose 文件使用。

## 6.2 验证 CRC16 与真实 Redis 一致

先运行单元测试：

```bash
mvn -pl notification-infrastructure -am \
  -Dtest=RedisClusterSlotTest,ShortLinkRedisKeysTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

再查询真实 Redis：

```bash
docker exec \
  -e REDISCLI_AUTH=notification123 \
  notification-redis-7001 \
  redis-cli -p 7001 CLUSTER KEYSLOT foo

docker exec \
  -e REDISCLI_AUTH=notification123 \
  notification-redis-7001 \
  redis-cli -p 7001 CLUSTER KEYSLOT bar
```

预期：

```text
foo → 12182
bar → 5061
```

## 6.3 验证同一短码的 Key 同槽

```bash
for key in \
  'shortlink:{aZ8k2LmP}:redirect' \
  'shortlink:{aZ8k2LmP}:negative' \
  'shortlink:{aZ8k2LmP}:click:count'; do
  docker exec \
    -e REDISCLI_AUTH=notification123 \
    notification-redis-7001 \
    redis-cli -p 7001 CLUSTER KEYSLOT "${key}"
done
```

三个结果必须完全相同。

再验证不同短码仍可分散：

```text
shortlink:{aZ8k2LmP}:redirect
shortlink:{Xy98Mn76}:redirect
```

它们通常不同槽。不要断言任意两个不同值绝对不同，因为 16384 个槽本身允许碰撞；自动化测试只选择已经确认落在不同槽的固定样本。

## 6.4 稳定复现 CROSSSLOT

先使用 `-c` 写入两个不同 Tag 的 Key：

```bash
docker exec \
  -e REDISCLI_AUTH=notification123 \
  notification-redis-7001 \
  redis-cli -c -p 7001 SET 'shortlink:{A}:redirect' A

docker exec \
  -e REDISCLI_AUTH=notification123 \
  notification-redis-7001 \
  redis-cli -c -p 7001 SET 'shortlink:{B}:redirect' B
```

执行跨槽 MGET：

```bash
docker exec \
  -e REDISCLI_AUTH=notification123 \
  notification-redis-7001 \
  redis-cli -c -p 7001 \
  MGET 'shortlink:{A}:redirect' 'shortlink:{B}:redirect'
```

预期：

```text
CROSSSLOT Keys in request don't hash to the same slot
```

再执行同槽 MGET：

```bash
docker exec \
  -e REDISCLI_AUTH=notification123 \
  notification-redis-7001 \
  redis-cli -c -p 7001 \
  MGET 'shortlink:{A}:redirect' 'shortlink:{A}:negative'
```

这次不会出现 CROSSSLOT。

## 6.5 验证旧 Bloom Lua 为什么会失败

```bash
docker exec \
  -e REDISCLI_AUTH=notification123 \
  notification-redis-7001 \
  redis-cli -c -p 7001 \
  EVAL "return {KEYS[1],KEYS[2]}" 2 \
  shortlink:bloom:ready:v1 \
  shortlink:bloom:codes:v1
```

如果两个旧 Key 不同槽，会得到 CROSSSLOT。

新 Key：

```bash
docker exec \
  -e REDISCLI_AUTH=notification123 \
  notification-redis-7001 \
  redis-cli -c -p 7001 \
  EVAL "return {KEYS[1],KEYS[2]}" 2 \
  'shortlink:{bloom:v2}:ready' \
  'shortlink:{bloom:v2}:bitmap'
```

必须成功返回两个 Key。

## 6.6 运行真实 Cluster 集成测试

```bash
REDIS_CLUSTER_NODES='127.0.0.1:7001,127.0.0.1:7002,127.0.0.1:7003,127.0.0.1:7004,127.0.0.1:7005,127.0.0.1:7006' \
REDIS_CLUSTER_PASSWORD='notification123' \
mvn -pl notification-infrastructure -am \
  -Dtest=RedisShortLinkClusterIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

验收：

- 同 Tag MGET 成功；
- 两个不同槽位的短码通过 `getAll` 都能读回；
- 没有 CROSSSLOT；
- 测试结束后 Key 被清理。

## 6.7 观察 MOVED

先查询某个 Key 的 slot 和负责节点：

```bash
docker exec \
  -e REDISCLI_AUTH=notification123 \
  notification-redis-7001 \
  redis-cli -p 7001 CLUSTER KEYSLOT 'shortlink:{A}:redirect'
```

然后故意连接一个不负责该 slot 的节点，且不使用 `-c` 执行 GET。预期看到：

```text
MOVED <slot> <host>:<port>
```

加上 `-c` 后，redis-cli 会自动跟随重定向并返回结果。这对应 Lettuce 在应用中的职责。

## 6.8 观察 ASK

ASK 只在槽位迁移窗口出现。执行：

```bash
docker exec \
  -e REDISCLI_AUTH=notification123 \
  -it notification-redis-7001 \
  redis-cli --cluster reshard 127.0.0.1:7001
```

迁移少量槽位，并在另一个终端持续读取迁移槽位中的 Key：

```bash
while true; do
  docker exec \
    -e REDISCLI_AUTH=notification123 \
    notification-redis-7001 \
    redis-cli -p 7001 GET '你的迁移槽位测试Key'
done
```

不带 `-c` 时可能观察到 ASK；带 `-c` 时客户端会发送 `ASKING` 并完成临时路由。实验结束后使用 `CLUSTER NODES` 确认所有槽位稳定归属。

## 6.9 验证主从切换与业务降级

先启动 Server 的 Cluster profile，并访问一条真实短链完成缓存预热。然后从 `CLUSTER NODES` 选择一个 master：

```bash
docker stop notification-redis-7001
```

不要假设 7001 永远是目标 Key 的 master，必须先看槽位归属。

观察：

1. 故障检测窗口内可能出现 Redis 超时日志；
2. 短链正缓存失败后仍能回源 MySQL；
3. Bloom 失败时不会误伤合法短链；
4. 大约超过 `cluster-node-timeout` 后，相应副本晋升；
5. Lettuce 刷新拓扑后缓存访问恢复；
6. 重启旧节点后，它应以副本身份重新加入，而不是形成两个主节点。

恢复：

```bash
docker start notification-redis-7001
```

需要记录：故障开始时间、第一次失败、第一次成功、副本晋升时间和完整恢复时间。

## 6.10 验证 Key 改名后的缓存兼容

旧正缓存和负缓存不会被新代码读取，但它们都有短 TTL：

```text
旧正缓存：最多约 30 分钟
旧负缓存：约 2 分钟 + 30 秒抖动
```

发布后第一次请求允许回源 MySQL 并写入新 Key。验证期间查询数据库 QPS，确认没有不可接受的缓存冷启动洪峰。

Bloom v2 必须走完整重建：

```text
先删除 v2 ready
→ 写完 v2 bitmap
→ 最后设置 v2 ready=1
→ 本机 bloomTrusted=true
```

## 6.11 完整回归

```bash
mvn test
```

必须确保：

- 单机默认 profile 的既有测试仍通过；
- Cluster 专用测试在显式环境变量下通过；
- Day17 幂等和真实 MySQL 测试没有回归；
- Redis 不可用时短链创建、跳转正确性不受影响。

## 6.12 Day18 验收清单

```text
[ ] 能手算并用代码解释 slot = CRC16(hashKey) & 0x3FFF
[ ] 应用计算的 foo/bar 槽位与 Redis 一致
[ ] 能解释 Hash Tag 的第一对非空大括号规则
[ ] 同一 shortCode 的 redirect/negative/count Key 同槽
[ ] 不同 shortCode 没有使用全局 Tag 聚到单槽
[ ] Bloom ready 与 bitmap 同槽，Lua 不再 CROSSSLOT
[ ] 限流器原有三个 Key 仍以 tenantId 同槽
[ ] 跨槽 MGET 能稳定复现 CROSSSLOT
[ ] getAll 能按 slot 分组并合并结果
[ ] 能区分 MOVED 与 ASK
[ ] Lettuce adaptive + periodic topology refresh 已配置
[ ] 3 主 3 从能够完成主从切换实验
[ ] Redis 切换期间合法短链不会被误伤
[ ] 已记录故障检测、晋升和恢复时间
[ ] 默认单机开发环境仍可使用
[ ] mvn test 完整回归通过
```

## 6.13 实验记录模板

```markdown
### Day18 Redis Cluster 实验记录

- 日期：
- Git commit：
- Redis 版本：
- Spring Boot / Spring Data Redis 版本：
- Docker Desktop host networking 是否启用：
- 节点布局：3 master + 3 replica
- CRC16 已知样本：foo=12182，bar=5061
- 同槽 Key 与 slot：
- 跨槽 Key 与 CROSSSLOT 原始结果：
- MOVED 原始结果：
- ASK/reshard 观察结果：
- 停止的 master：
- 故障开始时间：
- 副本晋升时间：
- 第一次业务恢复时间：
- 故障期间短链响应：
- MySQL 回源变化：
- 完整回归测试结果：
- 未解决问题：
```

---

# 七、面试追问

## 1. Redis Cluster 为什么是 16384 个槽？

槽位是 Key 与节点之间的稳定路由层。扩缩容只迁移槽位，不需要因为节点数变化而重算所有 Key。16384 在迁移粒度、心跳传播的槽位 Bitmap 大小和集群元数据成本之间取得平衡。

## 2. Redis Cluster 的槽位怎么计算？

先按规则提取第一对非空 `{...}` 中的 Hash Tag；没有有效 Tag 就使用完整 Key。然后对 UTF-8 字节执行 CRC16-XMODEM，最后 `& 0x3FFF` 得到 0—16383。

## 3. Redis Cluster 为什么需要 Hash Tag？

Cluster 默认让不同 Key 独立分布，但 Lua、事务、MGET/MSET 等多 Key 操作要求 Key 同槽。Hash Tag 让相关 Key 只对相同片段计算槽位，从而既能同槽操作，又不必把所有数据聚到一个节点。

## 4. Hash Tag 能解决热 Key 吗？

不能。它甚至可能让相关数据更集中。Hash Tag 解决的是多 Key 路由约束；热 Key 需要本地缓存、读副本、业务拆分、请求合并、限流或数据分片等手段。

## 5. 为什么不能给所有短链都用 `{shortlink}`？

这样所有 Key 都落到同一槽位、同一主节点，失去 Cluster 水平分布意义。正确粒度是 `{shortCode}`，只让同一业务对象的相关 Key 同槽。

## 6. CROSSSLOT 一般在什么场景出现？

常见于 MGET/MSET、Lua 的多个 KEYS、MULTI/EXEC 和要求同槽的集合运算。单条 GET/SET 通常不会出现。

## 7. 批量查询跨槽 Key 怎么处理？

先对真实 Redis Key 计算 slot，按 slot 分组，每组执行一次同槽 MGET，再合并结果。需要限制批量大小、总超时和并发，不能无界地向所有节点并发。

## 8. Pipeline 与 MGET 有什么区别？

MGET 是一个多 Key 命令，在 Cluster 中要求同槽。Pipeline 是客户端把多条命令减少网络等待，不提供原子性；集群客户端可能按节点拆分 pipeline。两者不能混为一谈。

## 9. MOVED 和 ASK 有什么区别？

MOVED 表示槽位已永久属于另一个节点，客户端应更新拓扑；ASK 表示槽位迁移中的单次临时路由，客户端只对当前请求发送 ASKING，不应立即永久修改槽位归属。

## 10. 客户端收到 MOVED 后只重试一次够吗？

不够。当前请求需要重定向，后续请求还需要刷新拓扑；同时要有限重试、连接超时和命令超时，避免拓扑抖动时无限重试放大故障。

## 11. seed node 挂了，Cluster 就不可用吗？

seed node 只用于初始发现。客户端建立完整拓扑和连接后，并非所有请求都经过 seed。但应用冷启动时如果配置的全部 seed 都不可达，仍然无法发现集群，所以应配置多个节点。

## 12. 为什么实验要 3 主 3 从，而不是只有 3 个节点？

三个主节点只能验证分片和重定向，主节点停止后没有副本可以晋升，无法验证自动故障转移。3 主 3 从才形成最小的分片加高可用实验。

## 13. Redis Cluster 能保证强一致吗？

不能。Redis 主从复制通常是异步的，主节点写成功但尚未复制就故障，可能丢失最近写入。Cluster 提供分片和可用性，不等于跨节点强一致数据库。

## 14. 为什么短链 Redis 故障可以 fail-open？

Redis 在跳转链路中是性能层，MySQL 才是事实来源。缓存、负缓存或 Bloom 不可用时回源数据库会增加负载，但不会误伤合法短链。若 fail-closed，Redis 故障会直接变成业务不可用。

## 15. Bloom Filter 为什么必须把 ready 和 bitmap 放同槽？

检查脚本需要原子地确认 ready 后再读取 bitmap。Cluster 中一个 Lua 脚本的所有 KEYS 必须同槽，否则脚本在执行前就被 CROSSSLOT 拒绝。

## 16. 为什么 Bloom 使用全局 Tag，而短链缓存按 shortCode Tag？

当前 Bloom 本来就是一个全局 Bitmap，ready 是它的元数据，二者必须同槽；短链缓存是大量独立对象，需要按 shortCode 分散。二者的数据模型不同。

## 17. Redis 故障时限流也应该 fail-open 吗？

不能一概而论。营销通知可能为了保护渠道成本选择 fail-closed，验证码可能需要本地小额度保底。应根据业务损失、渠道容量和攻击风险设计，而不是照搬缓存策略。

## 18. Hash Tag 里应该放 tenantId 还是 shortCode？

取决于必须原子操作的边界。限流脚本跨 bucket/quota/decision，所以以 tenantId 同槽；公共短链解析以全局唯一 shortCode 为业务对象，所以以 shortCode 同槽。若把整个租户数据放同槽，大租户可能制造热槽。

## 19. 为什么不直接复制知识星球项目的 Redisson 实现？

通知平台已经使用 Spring Data Redis + Lettuce，后者能完成 Cluster 路由、重定向和拓扑刷新。为了展示技术栈再引入 Redisson会增加两套连接池、配置和故障语义，收益不足。

## 20. 如何证明 Redis Cluster 故障恢复真的有效？

不能只看 `cluster_state:ok`。需要同时记录节点角色变化、客户端重定向、业务请求成功率、MySQL 回源、首次失败与恢复时间，并验证故障期间没有把合法短链误判为不存在。

---

# 八、Day18 最终总结

Day18 的核心不是“把 host/port 改成 nodes”，而是建立以下完整认识：

```text
Key 设计决定槽位
槽位决定节点路由
Hash Tag 决定相关 Key 能否做多 Key 操作
按槽分组决定批处理能否在 Cluster 中运行
MOVED/ASK 与拓扑刷新决定迁移期间的客户端行为
主从切换决定节点故障后的恢复能力
fail-open/fail-closed 决定基础设施故障是否升级为业务故障
```

通知平台最终保留的边界是：

```text
MySQL 保存短链事实；
Redis Cluster 提供分布式缓存和保护能力；
同一短码使用 {shortCode}；
同一 Lua 原子边界必须同槽；
跨短码批量按 slot 分组；
Redis 故障不能误伤合法短链。
```

完成实验后，面试时应能用项目中的真实例子回答：

> 我把通知平台从单机 Redis 切换到 Cluster 时，先审查了所有多 Key 操作。普通 GET/SET 不受影响，但 Bloom 检查 Lua 同时使用 ready 和 bitmap，原 Key 会触发 CROSSSLOT，因此我用 `{bloom:v2}` 把它们放到同一槽。短链正缓存和负缓存按 `{shortCode}` 聚合，避免使用全局 Tag 形成热槽。批量查询先用 Redis 官方 CRC16 算法按 slot 分组，再逐槽 MGET。客户端使用 Lettuce 自适应和周期拓扑刷新处理 MOVED/ASK。主节点切换窗口内，缓存和 Bloom fail-open 回源 MySQL，所以性能会下降，但合法短链不会被误判。
