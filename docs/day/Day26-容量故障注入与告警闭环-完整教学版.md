# Day26：容量、故障注入与告警闭环（完整教学版）

> 真实代码基线：`4f6bd677a35f6af169467c24ad1741c8562c589b`
>
> 学习计划已对齐：原《30 天高级 Java 后端自研转岗冲刺计划》确定的 Micrometer + Prometheus + Grafana、压测与高可用目标，以及《多租户统一通知平台-补充学习计划》Day26 的“容量、故障注入与告警闭环”。当前 Day24、Day25 尚未实施，所以本文不会伪造迁移 lag 指标；等在线迁移代码真实存在后，再把迁移 checkpoint、双写失败数和 lag 接入同一套监控。
>
> 本文只新增教学文档，不直接修改项目源码。第五部分是你学习 Day26 时基于当前仓库手动完成的完整增量代码；每段代码都说明了职责和关键取舍。

## 一、原理

### 1.1 高可用不是“用了多少组件”，而是可验证的闭环

当前项目已经用了 MySQL、Redis、RocketMQ、Outbox、Bloom Filter、限流、隔离线程池和熔断器，但“组件存在”不等于“系统高可用”。完整闭环必须回答六个问题：

```text
发生了什么故障
    ↓
系统如何检测
    ↓
业务如何降级或拒绝
    ↓
什么时候触发告警
    ↓
故障恢复后如何确认积压收敛
    ↓
告警是否自动变成 resolved
```

Day26 的目标不是再引入一个中间件，而是让每一种关键故障都有证据链：

```text
故障注入命令
→ 业务现象
→ 指标变化
→ Prometheus 告警 firing
→ Alertmanager 通知
→ 故障恢复
→ 指标恢复
→ Alertmanager resolved
→ 恢复时间记录
```

### 1.2 SLI、SLO、告警条件必须分开

- **SLI**：实际测量值，例如渠道 P95、Outbox backlog、MQ ready messages。
- **SLO**：希望达到的目标，例如 99% 通知在 60 秒内进入终态。
- **告警条件**：需要人工介入的持续异常，例如 Outbox backlog 连续 2 分钟超过 100。

错误做法是“只要指标大于 0 就报警”。例如 Outbox 短暂出现 2 条待发布事件是正常状态；真正危险的是数量持续增长或最老事件年龄持续增大。

因此容量指标至少要同时看：

```text
数量：backlog 有多少
年龄：最老的一条等了多久
速率：进入速度与处理速度谁更快
```

### 1.3 Counter、Gauge、Timer 的选择

| 类型 | 特点 | 本项目例子 |
|---|---|---|
| Counter | 只能递增，进程重启后归零 | MQ 初次消费次数、重试消费次数、DLQ 处理次数 |
| Gauge | 可增可减，是当前快照 | Outbox backlog、Bloom trusted、熔断状态、线程池队列 |
| Timer/Histogram | 记录次数与耗时分布 | 渠道调用耗时、MQ 消费耗时、HTTP 耗时 |

Counter 不直接看绝对值，通常看：

```promql
rate(notification_mq_consume_total[5m])
increase(notification_mq_dlq_received_total[5m])
```

Timer 在 Prometheus 中会暴露 `_count`、`_sum` 和 `_bucket`，P95 应由 Histogram 计算：

```promql
histogram_quantile(
  0.95,
  sum by (le, provider) (
    rate(notification_channel_call_duration_seconds_bucket[5m])
  )
)
```

### 1.4 指标的真相源不能选错

不同问题必须从不同真相源获取：

| 问题 | 真相源 | 原因 |
|---|---|---|
| Outbox 是否积压 | MySQL `notify_outbox` | 状态和创建时间最终保存在数据库 |
| RocketMQ 是否积压 | Broker offset/原生指标 | Worker 只知道自己收到的消息，不知道 Broker 还有多少未拉取 |
| Bloom 是否可信 | `ShortLinkProtection.isBloomReady()` | ready、当前片和本机 dirty/trusted 共同决定 |
| 渠道是否慢 | Worker 调用 Timer | 耗时发生在业务调用现场 |
| 熔断器是否打开 | Worker 内存状态机 | 状态只存在当前 Worker 进程 |

因此不能用“生产数 - Worker 日志消费数”模拟 MQ backlog。RocketMQ 5.3.2 已能在 Broker 直接暴露 Prometheus 指标，包括 ready、inflight、lag latency 和 DLQ 数量，本次直接启用它。

### 1.5 为什么不在 Prometheus 抓取时直接查数据库

最简单的 Gauge 写法是：

```java
Gauge.builder("notification.outbox.backlog", repository,
        value -> value.countPending())
```

这会让每一次 `/actuator/prometheus` 抓取都执行 SQL。Prometheus 5 秒抓一次、Grafana 和人工又同时查询时，监控会反过来给数据库制造压力；数据库故障时还可能拖慢指标接口。

本次使用“后台采样 + 内存 Gauge”：

```text
定时任务每 5 秒查询一次 MySQL
        ↓
更新 AtomicLong
        ↓
Prometheus 只读取内存 Gauge
```

同时增加 `refresh_success` 和 `last_success_age_seconds`。数据库故障后旧快照可能还留在内存，不能把旧值误当成实时值。

### 1.6 告警为什么需要 pending、firing、resolved

Prometheus 规则中的 `for` 用于抑制毛刺：

```text
表达式第一次为真        → pending
持续满足 for            → firing
表达式恢复为假          → resolved
```

例如：

```yaml
expr: notification_outbox_backlog{status="pending"} > 100
for: 2m
```

一次瞬时峰值不会报警；持续 2 分钟才说明处理能力长期低于流入速度。Alertmanager 负责分组、去重、静默和通知，并在 `send_resolved: true` 时发送恢复通知。

### 1.7 标签基数为什么可能拖垮 Prometheus

每一组标签值都会生成一条新的时间序列。以下字段禁止成为指标标签：

```text
tenantId
messageId
requestId
eventId
receiver
traceId
shortCode
```

它们的取值会无限增长。Day26 只使用低基数标签：

```text
application
status
channel
provider
outcome
kind(initial/retry)
```

租户问题需要通过日志、审计表或离线分析定位，不能把每个租户直接变成 Prometheus 标签。

官方资料可作为本节延伸阅读：

- [Prometheus 指标埋点与标签基数实践](https://prometheus.io/docs/practices/instrumentation/)
- [Micrometer Gauge 与强引用注意事项](https://docs.micrometer.io/micrometer/reference/concepts/gauges.html)

### 1.8 fail-open 与 fail-closed 的业务边界

| 故障 | 当前项目行为 | 原因 |
|---|---|---|
| Redis/Bloom 不可用 | 短链 Bloom fail-open，回源 MySQL | 误放只增加数据库压力，误杀会让合法短链不可访问 |
| Redis 限流不可用 | 当前配置 `fail-open=true` | 优先保证通知可发送，但要告警并防止长期失控 |
| MySQL 不可用 | fail-closed | 无法确认任务、幂等和状态，继续发送可能产生重复或丢失 |
| RocketMQ 不可用 | HTTP 创建仍可提交，Outbox 暂存 | 本地事务已经保存业务事实，恢复后重新发布 |
| 渠道超时/结果未知 | 不切备用，交给同一事件重试 | 供应商可能已受理，切备用会造成重复触达 |
| 渠道明确拒绝 | 可切换备用供应商 | 可以确定主供应商没有受理 |

面试时不要说“Redis 挂了统一 fail-open”。正确答案取决于失败后的错误成本。

### 1.9 容量结论必须同时包含饱和点与恢复时间

Day14 已得到真实基线：通知端到端首先触达的是 5 token/s 渠道令牌桶，而不是 CPU、Hikari 或线程池。10 RPS 压测后出现积压，停止施压后约 3 分钟收敛。

Day26 需要把一次性观察升级成可回溯指标，并记录：

```text
稳定吞吐
首个饱和资源
饱和时输入速率
积压峰值
停止施压时间
积压归零时间
恢复耗时
预留容量
```

没有测到上限时只能说“至少达到 200 RPS”，不能写成“系统容量为 200 RPS”，更不能写“百万 QPS”。

### 1.10 为什么 Grafana、Prometheus、Alertmanager 不能混为一谈

```text
应用 / RocketMQ Broker
→ 暴露指标
→ Prometheus 抓取、存储、执行告警规则
→ Alertmanager 分组、去重、路由、发送 firing/resolved
→ Grafana 查询 Prometheus 并展示时间趋势
```

Grafana 的数据源和 Dashboard 使用文件 provisioning，确保环境可重建；Prometheus 告警规则仍由 Prometheus 执行。本次采用的配置方式可对应查看：

- [Prometheus 告警概览](https://prometheus.io/docs/alerting/latest/overview/)
- [Alertmanager 配置](https://prometheus.io/docs/alerting/latest/configuration/)
- [Grafana provisioning](https://grafana.com/docs/grafana/latest/administration/provisioning/)
- [RocketMQ 5 原生指标与 Prometheus Exporter](https://rocketmq.apache.org/docs/observability/01metrics/)

## 二、现有数据流

### 2.1 当前监控数据流

```text
notification-server :8080/actuator/prometheus ─┐
                                                 ├→ Prometheus :9090
notification-worker :8081/actuator/prometheus ─┘
```

现有 `deploy/prometheus/prometheus.yml` 每 5 秒抓取两个应用。Server 和 Worker 都已经引入 Actuator 与 Prometheus Registry。

当前还缺少：

```text
Grafana                    缺失
Alertmanager               缺失
Prometheus alert rules     缺失
RocketMQ Broker metrics    未启用
告警接收与 resolved 验证   缺失
```

### 2.2 当前已有的业务指标

Worker 已经通过 `ChannelMetrics` 和 `ChannelCallExecutor` 暴露：

```text
notification.channel.call.duration
notification.channel.failover
notification.channel.circuit.rejected
notification.channel.circuit.state
notification.channel.executor.active
notification.channel.executor.pool.size
notification.channel.executor.queued
notification.channel.executor.queue.remaining
notification.channel.executor.completed
notification.channel.executor.rejected
```

这部分直接复用，不重复创建另一套渠道指标。

### 2.3 当前 Outbox 数据流

```text
HTTP 创建任务
→ notify_task / notify_message / notify_outbox 同事务提交
→ OutboxScheduler 每秒查 NEW、到期 FAILED、超时 PROCESSING
→ CAS claim
→ RocketMQ publish
→ PUBLISHED 或 FAILED/DEAD
```

数据库已经有完整状态，但没有 Gauge。Day14 只能临时执行 SQL 观察，无法在 Grafana 回看峰值，也无法根据“最老待发布事件年龄”报警。

还有一个必须在故障实验前看见的真实边界：当前 `notification.outbox.max-retry-count=3`，Broker 持续不可用约十几秒后事件就可能进入 `DEAD`；而 `findClaimable` 不会自动扫描 `DEAD`。如果保持这个配置，却宣称“Broker 两分钟故障恢复后 Outbox 自动收敛”，结论就是假的。Day26 把最大尝试次数调整为 20，让分钟级基础设施故障保持为可自动恢复的 `FAILED`；永久错误最终仍会进入 `DEAD` 并要求人工 Runbook。

### 2.4 当前 RocketMQ 数据流

```text
Broker
→ notification-worker-group
→ NotificationSendListener
→ NotificationSendOrchestrator
→ 成功确认 / 抛异常触发重投
→ 超过最大次数进入 %DLQ%notification-worker-group
→ NotificationDeadLetterListener 落库
```

日志里已有 `reconsumeTimes`，但日志不能回答 Broker 当前 ready/inflight 数量。当前 Broker 也没有打开 5557 Prometheus 端口。

### 2.5 当前 Bloom 故障数据流

```text
短链请求
→ 本地缓存
→ Redis 缓存
→ Bloom trusted?
   ├─ false：fail-open 查询 MySQL
   └─ true：Bloom 判断
→ 找到映射后回填缓存
```

`RedisShortLinkProtection` 已经具备 `bloomTrusted`、`bloomDirty`、ready 和重建锁，但外部无法看到当前是否可信。

### 2.6 当前渠道慢调用数据流

```text
receiver=provider-slow:mock-sms-primary:...
→ Mock 主供应商 sleep 5 秒
→ ChannelCallExecutor 2 秒超时并中断
→ 记录 TIMEOUT
→ 熔断失败计数增长
→ 达阈值后 OPEN
→ 结果未知，不自动切备用
```

这是已经存在的真实故障注入入口，Day26 直接复用。

### 2.7 当前容量证据

`performance/day14/report.md` 已记录：

- 短链热点至少稳定 200 RPS，未测到上限；
- 通知创建 10 RPS 时 HTTP 正常，但下游受 5 token/s 限流产生积压；
- CPU、Hikari Pending 和渠道线程池队列没有先饱和；
- 停止压力后积压最终收敛。

Day26 不推翻这份结果，而是补上可持续监控与故障恢复记录。

### 2.8 当前不存在的数据流

Day24、Day25 尚未学习，因此当前仓库没有：

```text
新旧路由双写
回填 checkpoint
迁移失败补偿
新旧数据 checksum
灰度切读
迁移 lag
```

Day26 不新增一个永远为 0 的假 `migration_lag` Gauge。等迁移实现后，指标必须从真实 checkpoint 和补偿表读取。

同理，Day23 的 2 库 × 4 表仍是 test-scope PoC，Server/Worker 的默认生产数据源尚未切到 ShardingSphere。当前可以复用 `ShardRoutingSimulatorTest` 和 Day23 物理表统计证明算法分布，但不能伪造一个“线上分片倾斜”实时指标。未来真实启用分片后，应按物理节点采集行数、写入速率和最大/平均比值，并设置倾斜阈值。

## 三、本次需要改动的数据流

### 3.1 Outbox backlog 指标流

```text
OutboxMetrics（每 5 秒）
→ OutboxObservabilityRepository
→ JdbcTemplate 跨租户只读聚合
→ pending / dead / oldest age
→ AtomicLong
→ /actuator/prometheus
→ Prometheus rule
→ Alertmanager
```

跨租户查询只返回聚合数，不暴露租户业务数据；它属于平台运维能力，不走普通 TenantContext。

### 3.2 Bloom 信任指标流

```text
ShortLinkBloomMetrics（每 15 秒）
→ ShortLinkProtection.isBloomReady()
→ ready + 当前片 + dirty/trusted
→ trusted=1/0
→ Prometheus
→ ShortLinkBloomUntrusted
```

Redis 故障时业务继续 fail-open，但告警必须 firing；Redis 恢复并完成重建后 trusted 回到 1，告警自动 resolved。

### 3.3 RocketMQ 指标流

```text
RocketMQ Broker 5.3.2
→ metricsExporterType=PROM
→ broker:5557/metrics
→ Prometheus
→ ready / inflight / lag / DLQ
```

Worker 侧另外记录初次消费、重试消费、成功/失败耗时和 DLQ Listener 收到数，用于区分“Broker 有积压”与“业务处理持续失败”。

### 3.4 告警通知闭环

```text
Prometheus rule pending
→ 持续达到 for
→ firing
→ Alertmanager 分组/去重
→ MailHog 收到 FIRING 邮件
→ 修复故障
→ 表达式恢复
→ resolved
→ MailHog 收到 RESOLVED 邮件
```

MailHog 只用于本地告警实验，不是 Day29 的真实业务邮件渠道。

### 3.5 Grafana 数据流

```text
Grafana 启动
→ provisioning 自动加载 Prometheus 数据源
→ 自动加载 Day26 dashboard JSON
→ 展示应用存活、Outbox、Bloom、MQ、DLQ、渠道 P95、熔断状态
```

配置全部进入 Git，避免“我本机手工点过一个面板，但别人启动后什么都没有”。

### 3.6 故障恢复记录流

每次实验记录四个时间点：

```text
T0 注入故障
T1 告警 firing
T2 恢复组件
T3 backlog=0 且告警 resolved
```

指标：

```text
检测耗时 = T1 - T0
恢复耗时 = T3 - T2
总影响时间 = T3 - T0
```

## 四、文件位置（复用 / 新增 / 修改）

### 4.1 复用：当前真实代码

- `notification-worker/.../resilience/ChannelMetrics.java`
- `notification-worker/.../resilience/ChannelCallExecutor.java`
- `notification-worker/.../resilience/ChannelCircuitBreaker.java`
- `notification-channel/.../AbstractMockChannelSender.java`
- `notification-server/.../config/ShortLinkBloomInitializer.java`
- `notification-infrastructure/.../shortlink/RedisShortLinkProtection.java`
- `notification-server/.../outbox/OutboxScheduler.java`
- `performance/day14/report.md`

### 4.2 新增：Java 指标代码

```text
notification-core/src/main/java/com/tam/notification/domain/observability/
├── OutboxBacklogSnapshot.java
└── OutboxObservabilityRepository.java

notification-infrastructure/src/main/java/com/tam/notification/observability/
└── JdbcOutboxObservabilityRepository.java

notification-server/src/main/java/com/tam/notification/observability/
├── OutboxMetrics.java
└── ShortLinkBloomMetrics.java

notification-worker/src/main/java/com/tam/notification/observability/
└── MqConsumeMetrics.java
```

### 4.3 修改：接入指标

```text
notification-worker/.../listener/NotificationSendListener.java
notification-worker/.../listener/NotificationDeadLetterListener.java
notification-server/src/main/resources/application.yml
notification-worker/src/main/resources/application.yml
```

### 4.4 新增：自动化测试

```text
notification-server/src/test/java/com/tam/notification/observability/OutboxMetricsTest.java
notification-server/src/test/java/com/tam/notification/observability/ShortLinkBloomMetricsTest.java
notification-worker/src/test/java/com/tam/notification/observability/MqConsumeMetricsTest.java
```

### 4.5 新增/修改：监控基础设施

```text
修改 deploy/docker-compose.yml
修改 deploy/rocketmq/broker.conf
修改 deploy/prometheus/prometheus.yml
新增 deploy/prometheus/rules/notification-alerts.yml
新增 deploy/alertmanager/alertmanager.yml
新增 deploy/grafana/provisioning/datasources/prometheus.yml
新增 deploy/grafana/provisioning/dashboards/default.yml
新增 deploy/grafana/dashboards/notification-day26.json
```

### 4.6 新增：容量实验资产

```text
performance/day26/notification-capacity.js
performance/day26/report.md
```

### 4.7 明确不修改

- 不把 ShardingSphere test-scope PoC 切成生产数据源；
- 不新增虚假的迁移 lag 指标；
- 不把 tenantId、messageId、receiver 放进标签；
- 不改变当前 Redis fail-open、MySQL fail-closed 语义；
- 不通过调大线程池掩盖 5 token/s 渠道限流瓶颈；
- 不删除 Day14 报告或篡改历史实测数字。

## 五、基于现有代码的完整增量代码

### 5.1 新增 `OutboxBacklogSnapshot.java`

位置：`notification-core/src/main/java/com/tam/notification/domain/observability/OutboxBacklogSnapshot.java`

```java
package com.tam.notification.domain.observability;

/**
 * Outbox 运维快照。
 *
 * <p>这里只携带跨租户聚合结果，不携带任何租户或消息明细。</p>
 */
public record OutboxBacklogSnapshot(
        long pendingCount,
        long deadCount,
        long oldestPendingAgeSeconds
) {

    public OutboxBacklogSnapshot {
        if (pendingCount < 0
                || deadCount < 0
                || oldestPendingAgeSeconds < 0) {
            throw new IllegalArgumentException("Outbox 指标不能为负数");
        }
    }
}
```

### 5.2 新增 `OutboxObservabilityRepository.java`

位置：`notification-core/src/main/java/com/tam/notification/domain/observability/OutboxObservabilityRepository.java`

```java
package com.tam.notification.domain.observability;

/**
 * 平台级 Outbox 可观测性端口。
 *
 * <p>业务 Repository 继续负责保存、Claim 和状态流转；
 * 该端口只负责低频只读聚合，避免把监控 SQL 放进 Server。</p>
 */
public interface OutboxObservabilityRepository {

    OutboxBacklogSnapshot loadSnapshot();
}
```

### 5.3 新增 `JdbcOutboxObservabilityRepository.java`

位置：`notification-infrastructure/src/main/java/com/tam/notification/observability/JdbcOutboxObservabilityRepository.java`

```java
package com.tam.notification.observability;

import com.tam.notification.domain.observability.OutboxBacklogSnapshot;
import com.tam.notification.domain.observability.OutboxObservabilityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 使用一条聚合 SQL 读取平台级 Outbox 状态。
 *
 * <p>这里故意不走 TenantLineInterceptor，因为监控需要看到全平台积压；
 * SQL 只返回数量和年龄，不返回跨租户业务数据。</p>
 */
@Repository
@RequiredArgsConstructor
public class JdbcOutboxObservabilityRepository
        implements OutboxObservabilityRepository {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public OutboxBacklogSnapshot loadSnapshot() {
        OutboxBacklogSnapshot snapshot = jdbcTemplate.queryForObject(
                """
                SELECT
                    COALESCE(SUM(
                        CASE
                            WHEN publish_status IN ('NEW', 'FAILED', 'PROCESSING')
                            THEN 1 ELSE 0
                        END
                    ), 0) AS pending_count,
                    COALESCE(SUM(
                        CASE WHEN publish_status = 'DEAD' THEN 1 ELSE 0 END
                    ), 0) AS dead_count,
                    COALESCE(
                        TIMESTAMPDIFF(
                            SECOND,
                            MIN(
                                CASE
                                    WHEN publish_status IN ('NEW', 'FAILED', 'PROCESSING')
                                    THEN created_at
                                    ELSE NULL
                                END
                            ),
                            NOW(3)
                        ),
                        0
                    ) AS oldest_pending_age_seconds
                FROM notify_outbox
                """,
                (resultSet, rowNum) -> new OutboxBacklogSnapshot(
                        resultSet.getLong("pending_count"),
                        resultSet.getLong("dead_count"),
                        resultSet.getLong("oldest_pending_age_seconds")
                )
        );

        if (snapshot == null) {
            // 聚合 SQL 正常情况下始终返回一行；防御性兜底避免 Gauge 线程 NPE。
            return new OutboxBacklogSnapshot(0, 0, 0);
        }
        return snapshot;
    }
}
```

### 5.4 新增 `OutboxMetrics.java`

位置：`notification-server/src/main/java/com/tam/notification/observability/OutboxMetrics.java`

```java
package com.tam.notification.observability;

import com.tam.notification.domain.observability.OutboxBacklogSnapshot;
import com.tam.notification.domain.observability.OutboxObservabilityRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Outbox 监控采样器。
 *
 * <p>数据库查询由定时任务执行，Prometheus 抓取只读取内存，
 * 避免抓取频率直接放大数据库压力。</p>
 */
@Slf4j
@Component
public class OutboxMetrics {

    private final OutboxObservabilityRepository repository;
    private final Clock clock;

    // Gauge 使用弱引用，因此这些状态必须保存在字段中，不能只建局部变量。
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong dead = new AtomicLong();
    private final AtomicLong oldestPendingAgeSeconds = new AtomicLong();
    private final AtomicLong refreshSuccess = new AtomicLong();
    private final AtomicLong lastSuccessEpochSeconds = new AtomicLong();

    @Autowired
    public OutboxMetrics(
            OutboxObservabilityRepository repository,
            MeterRegistry meterRegistry
    ) {
        this(repository, meterRegistry, Clock.systemUTC());
    }

    // 包级构造器允许测试注入固定时钟。
    OutboxMetrics(
            OutboxObservabilityRepository repository,
            MeterRegistry meterRegistry,
            Clock clock
    ) {
        this.repository = repository;
        this.clock = clock;

        Gauge.builder("notification.outbox.backlog", pending, AtomicLong::get)
                .description("未完成的 Outbox 事件数")
                .tag("status", "pending")
                .register(meterRegistry);

        Gauge.builder("notification.outbox.backlog", dead, AtomicLong::get)
                .description("进入 DEAD 的 Outbox 事件数")
                .tag("status", "dead")
                .register(meterRegistry);

        Gauge.builder(
                        "notification.outbox.oldest.pending.age.seconds",
                        oldestPendingAgeSeconds,
                        AtomicLong::get
                )
                .description("最老未完成 Outbox 事件年龄，单位秒")
                .register(meterRegistry);

        Gauge.builder(
                        "notification.outbox.metrics.refresh.success",
                        refreshSuccess,
                        AtomicLong::get
                )
                .description("最近一次 Outbox 指标刷新是否成功，1成功0失败")
                .register(meterRegistry);

        Gauge.builder(
                        "notification.outbox.metrics.last.success.age.seconds",
                        lastSuccessEpochSeconds,
                        this::lastSuccessAgeSeconds
                )
                .description("距离上次成功刷新 Outbox 指标的秒数，未成功过时为-1")
                .register(meterRegistry);
    }

    @Scheduled(
            fixedDelayString = "${notification.observability.outbox.refresh-interval-ms:5000}"
    )
    public void refresh() {
        try {
            OutboxBacklogSnapshot snapshot = repository.loadSnapshot();
            pending.set(snapshot.pendingCount());
            dead.set(snapshot.deadCount());
            oldestPendingAgeSeconds.set(snapshot.oldestPendingAgeSeconds());
            lastSuccessEpochSeconds.set(clock.instant().getEpochSecond());
            refreshSuccess.set(1);
        } catch (RuntimeException exception) {
            // 保留上一份业务快照，同时用 freshness 指标明确告诉监控“数据已过期”。
            refreshSuccess.set(0);
            log.error("refresh outbox metrics failed", exception);
        }
    }

    private double lastSuccessAgeSeconds(AtomicLong lastSuccess) {
        long epochSeconds = lastSuccess.get();
        if (epochSeconds == 0) {
            return -1;
        }
        return Math.max(0, clock.instant().getEpochSecond() - epochSeconds);
    }
}
```

### 5.5 新增 `ShortLinkBloomMetrics.java`

位置：`notification-server/src/main/java/com/tam/notification/observability/ShortLinkBloomMetrics.java`

```java
package com.tam.notification.observability;

import com.tam.notification.domain.shortlink.ShortLinkProtection;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 定期验证共享 ready、当前时间片与本机 trusted 是否一致。
 */
@Slf4j
@Component
public class ShortLinkBloomMetrics {

    private final ShortLinkProtection shortLinkProtection;
    private final AtomicInteger trusted = new AtomicInteger();

    public ShortLinkBloomMetrics(
            ShortLinkProtection shortLinkProtection,
            MeterRegistry meterRegistry
    ) {
        this.shortLinkProtection = shortLinkProtection;

        Gauge.builder(
                        "notification.shortlink.bloom.trusted",
                        trusted,
                        AtomicInteger::get
                )
                .description("时间分片 Bloom 当前是否可信，1可信0不可信")
                .register(meterRegistry);
    }

    @Scheduled(
            fixedDelayString = "${notification.observability.bloom.refresh-interval-ms:15000}"
    )
    public void refresh() {
        try {
            trusted.set(shortLinkProtection.isBloomReady() ? 1 : 0);
        } catch (RuntimeException exception) {
            // 当前实现通常会在内部吞掉 Redis 异常并返回 false，这里仍做边界兜底。
            trusted.set(0);
            log.error("refresh bloom trusted metric failed", exception);
        }
    }
}
```

### 5.6 新增 `MqConsumeMetrics.java`

位置：`notification-worker/src/main/java/com/tam/notification/observability/MqConsumeMetrics.java`

```java
package com.tam.notification.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Worker 业务消费指标。
 *
 * <p>Broker backlog 由 RocketMQ 原生指标负责；这里回答 Worker 收到后
 * 是初次处理还是重试、最终成功还是抛异常。</p>
 */
@Component
public class MqConsumeMetrics {

    private final MeterRegistry meterRegistry;
    private final Counter initialCounter;
    private final Counter retryCounter;
    private final Counter dlqReceivedCounter;
    private final Timer successTimer;
    private final Timer failureTimer;

    public MqConsumeMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        this.initialCounter = Counter.builder("notification.mq.consume")
                .description("Worker 收到的通知消息数")
                .tag("kind", "initial")
                .register(meterRegistry);

        this.retryCounter = Counter.builder("notification.mq.consume")
                .description("Worker 收到的通知消息数")
                .tag("kind", "retry")
                .register(meterRegistry);

        this.dlqReceivedCounter = Counter.builder("notification.mq.dlq.received")
                .description("业务 DLQ Listener 收到的死信数")
                .register(meterRegistry);

        this.successTimer = Timer.builder("notification.mq.consume.duration")
                .description("通知 MQ 消费处理耗时")
                .tag("outcome", "success")
                .publishPercentileHistogram()
                .register(meterRegistry);

        this.failureTimer = Timer.builder("notification.mq.consume.duration")
                .description("通知 MQ 消费处理耗时")
                .tag("outcome", "failure")
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    public Timer.Sample start(int reconsumeTimes) {
        if (reconsumeTimes > 0) {
            retryCounter.increment();
        } else {
            initialCounter.increment();
        }
        return Timer.start(meterRegistry);
    }

    public void recordSuccess(Timer.Sample sample) {
        sample.stop(successTimer);
    }

    public void recordFailure(Timer.Sample sample) {
        sample.stop(failureTimer);
    }

    public void recordDlqReceived() {
        dlqReceivedCounter.increment();
    }
}
```

### 5.7 修改 `NotificationSendListener.java`

在字段中新增：

```java
private final MqConsumeMetrics mqConsumeMetrics;
```

增加 import：

```java
import com.tam.notification.observability.MqConsumeMetrics;
import io.micrometer.core.instrument.Timer;
```

把 `onMessage` 完整替换为：

```java
@Override
public void onMessage(final MessageExt message) {
    Timer.Sample sample = mqConsumeMetrics.start(message.getReconsumeTimes());

    try {
        final var payload = new String(
                message.getBody(),
                StandardCharsets.UTF_8
        );

        NotificationSendEvent event = deserialize(payload);
        log.info(
                "收到通知消息，worker={}, eventId={}, messageId={}, "
                        + "mqMsgId={}, queueId={}, queueOffset={}, reconsumeTimes={}",
                workerIdentity.instanceId(),
                event.eventId(),
                event.messageId(),
                message.getMsgId(),
                message.getQueueId(),
                message.getQueueOffset(),
                message.getReconsumeTimes()
        );

        try {
            TenantContext.setTenantId(event.tenantId());
            if (event.traceId() != null) {
                TraceContext.setTraceId(event.traceId());
            }

            // 不吞异常：记录指标后仍要把异常交还 RocketMQ 触发重投。
            sendOrchestrator.send(event);
            mqConsumeMetrics.recordSuccess(sample);
        } finally {
            TenantContext.clear();
            TraceContext.clear();
        }
    } catch (RuntimeException | Error exception) {
        mqConsumeMetrics.recordFailure(sample);
        throw exception;
    }
}
```

注意：成功时 Timer 只停止一次；异常路径也只停止一次，不能放进无条件 `finally` 后又在 catch 中重复记录。

### 5.8 修改 `NotificationDeadLetterListener.java`

新增字段与 import：

```java
import com.tam.notification.observability.MqConsumeMetrics;

private final MqConsumeMetrics mqConsumeMetrics;
```

在 `onMessage` 第一行增加：

```java
@Override
public void onMessage(final MessageExt message) {
    // 先计数再反序列化，毒消息即使无法解析也不能从 DLQ 指标中消失。
    mqConsumeMetrics.recordDlqReceived();

    String payload = new String(
            message.getBody(),
            StandardCharsets.UTF_8
    );

    // 后续保留当前代码不变……
}
```

### 5.9 新增 `OutboxMetricsTest.java`

位置：`notification-server/src/test/java/com/tam/notification/observability/OutboxMetricsTest.java`

```java
package com.tam.notification.observability;

import com.tam.notification.domain.observability.OutboxBacklogSnapshot;
import com.tam.notification.domain.observability.OutboxObservabilityRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxMetricsTest {

    @Test
    void shouldPublishSnapshotAndFreshness() {
        OutboxObservabilityRepository repository =
                mock(OutboxObservabilityRepository.class);
        when(repository.loadSnapshot())
                .thenReturn(new OutboxBacklogSnapshot(12, 2, 45));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Clock clock = Clock.fixed(
                Instant.parse("2026-08-17T10:00:00Z"),
                ZoneOffset.UTC
        );

        OutboxMetrics metrics = new OutboxMetrics(
                repository,
                registry,
                clock
        );
        metrics.refresh();

        assertThat(registry.get("notification.outbox.backlog")
                .tag("status", "pending")
                .gauge()
                .value()).isEqualTo(12);
        assertThat(registry.get("notification.outbox.backlog")
                .tag("status", "dead")
                .gauge()
                .value()).isEqualTo(2);
        assertThat(registry.get("notification.outbox.oldest.pending.age.seconds")
                .gauge()
                .value()).isEqualTo(45);
        assertThat(registry.get("notification.outbox.metrics.refresh.success")
                .gauge()
                .value()).isEqualTo(1);
        assertThat(registry.get("notification.outbox.metrics.last.success.age.seconds")
                .gauge()
                .value()).isZero();
    }

    @Test
    void shouldMarkRefreshFailedWithoutReplacingLastSnapshot() {
        OutboxObservabilityRepository repository =
                mock(OutboxObservabilityRepository.class);
        when(repository.loadSnapshot())
                .thenReturn(new OutboxBacklogSnapshot(7, 0, 10))
                .thenThrow(new IllegalStateException("mysql down"));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxMetrics metrics = new OutboxMetrics(
                repository,
                registry,
                Clock.systemUTC()
        );

        metrics.refresh();
        metrics.refresh();

        assertThat(registry.get("notification.outbox.backlog")
                .tag("status", "pending")
                .gauge()
                .value()).isEqualTo(7);
        assertThat(registry.get("notification.outbox.metrics.refresh.success")
                .gauge()
                .value()).isZero();
    }
}
```

### 5.10 新增 `ShortLinkBloomMetricsTest.java`

```java
package com.tam.notification.observability;

import com.tam.notification.domain.shortlink.ShortLinkProtection;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShortLinkBloomMetricsTest {

    @Test
    void shouldExposeTrustedState() {
        ShortLinkProtection protection = mock(ShortLinkProtection.class);
        when(protection.isBloomReady()).thenReturn(true, false);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ShortLinkBloomMetrics metrics = new ShortLinkBloomMetrics(
                protection,
                registry
        );

        metrics.refresh();
        assertThat(registry.get("notification.shortlink.bloom.trusted")
                .gauge().value()).isEqualTo(1);

        metrics.refresh();
        assertThat(registry.get("notification.shortlink.bloom.trusted")
                .gauge().value()).isZero();
    }
}
```

### 5.11 新增 `MqConsumeMetricsTest.java`

```java
package com.tam.notification.observability;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MqConsumeMetricsTest {

    @Test
    void shouldSeparateInitialRetryFailureAndDlq() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MqConsumeMetrics metrics = new MqConsumeMetrics(registry);

        Timer.Sample initial = metrics.start(0);
        metrics.recordSuccess(initial);

        Timer.Sample retry = metrics.start(2);
        metrics.recordFailure(retry);

        metrics.recordDlqReceived();

        assertThat(registry.get("notification.mq.consume")
                .tag("kind", "initial")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("notification.mq.consume")
                .tag("kind", "retry")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("notification.mq.consume.duration")
                .tag("outcome", "success")
                .timer().count()).isEqualTo(1);
        assertThat(registry.get("notification.mq.consume.duration")
                .tag("outcome", "failure")
                .timer().count()).isEqualTo(1);
        assertThat(registry.get("notification.mq.dlq.received")
                .counter().count()).isEqualTo(1);
    }
}
```

### 5.12 修改两个 `application.yml`

在 Server 的 `notification:` 下增加：

```yaml
notification:
  outbox:
    # 当前退避最大 60 秒；20 次可覆盖分钟级 Broker 故障并自动恢复。
    # 永久失败最终仍进入 DEAD，不能无限重试。
    max-retry-count: 20

  observability:
    outbox:
      # 后台刷新数据库快照；Prometheus 抓取不直接执行 SQL。
      refresh-interval-ms: 5000
    bloom:
      # 独立探测 Bloom 信任状态，故障实验可在 15 秒内被观察。
      refresh-interval-ms: 15000
```

这是对当前 `max-retry-count: 3` 的修改。保留现有 `notification.mq`、`notification.outbox` 其余字段和 `notification.shortlink` 配置，不要重复创建第二个顶层 `notification:`。

在 Worker 的直方图配置中增加 MQ 消费耗时：

```yaml
management:
  metrics:
    distribution:
      percentiles-histogram:
        http.server.requests: true
        notification.channel.call.duration: true
        notification.mq.consume.duration: true

      slo:
        http.server.requests: 50ms,100ms,200ms,500ms,1s,2s
        notification.channel.call.duration: 50ms,100ms,200ms,500ms,1s,2s
        notification.mq.consume.duration: 50ms,100ms,500ms,1s,2s,5s,10s
```

### 5.13 修改 `deploy/rocketmq/broker.conf`

追加：

```properties
# Day26：由 RocketMQ 5 Broker 直接暴露 Prometheus 指标。
metricsExporterType=PROM
metricsPromExporterHost=0.0.0.0
metricsPromExporterPort=5557
```

设置 `0.0.0.0` 是为了让同一 Docker 网络内的 Prometheus 能抓取；不要依赖当前写死的宿主机 LAN `brokerIP1` 来绑定指标端口。

### 5.14 修改 `deploy/prometheus/prometheus.yml`

完整内容：

```yaml
global:
  scrape_interval: 5s
  evaluation_interval: 5s

rule_files:
  - /etc/prometheus/rules/*.yml

alerting:
  alertmanagers:
    - static_configs:
        - targets:
            - alertmanager:9093

scrape_configs:
  - job_name: notification-server
    metrics_path: /actuator/prometheus
    static_configs:
      - targets:
          - host.docker.internal:8080

  - job_name: notification-worker
    metrics_path: /actuator/prometheus
    static_configs:
      - targets:
          - host.docker.internal:8081

  - job_name: rocketmq-broker
    metrics_path: /metrics
    static_configs:
      - targets:
          - broker:5557
```

### 5.15 新增 `notification-alerts.yml`

位置：`deploy/prometheus/rules/notification-alerts.yml`

```yaml
groups:
  - name: notification-platform-availability
    rules:
      - alert: NotificationTargetDown
        expr: up{job=~"notification-server|notification-worker|rocketmq-broker"} == 0
        for: 30s
        labels:
          severity: critical
        annotations:
          summary: "{{ $labels.job }} target is down"
          description: "Prometheus 连续 30 秒无法抓取 {{ $labels.instance }}。"

      - alert: OutboxMetricsRefreshFailed
        expr: notification_outbox_metrics_refresh_success == 0
        for: 30s
        labels:
          severity: critical
        annotations:
          summary: "Outbox 指标刷新失败"
          description: "Server 无法读取 MySQL Outbox 快照，旧指标可能已经过期。"

      - alert: ShortLinkBloomUntrusted
        expr: notification_shortlink_bloom_trusted == 0
        for: 30s
        labels:
          severity: warning
        annotations:
          summary: "短链 Bloom 当前不可信"
          description: "短链会 fail-open 回源 MySQL，请检查 Redis、ready 和重建任务。"

  - name: notification-platform-backlog
    rules:
      - alert: OutboxBacklogHigh
        expr: notification_outbox_backlog{status="pending"} > 100
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "Outbox backlog 持续超过 100"
          description: "当前待发布 {{ $value }} 条，请检查 RocketMQ 或发布器。"

      - alert: OutboxOldestEventTooOld
        expr: notification_outbox_oldest_pending_age_seconds > 60
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "最老 Outbox 事件等待超过 60 秒"
          description: "积压年龄为 {{ $value }} 秒，已经影响发送时效。"

      - alert: OutboxDeadEventExists
        expr: notification_outbox_backlog{status="dead"} > 0
        for: 30s
        labels:
          severity: warning
        annotations:
          summary: "存在 Outbox DEAD 事件"
          description: "当前 DEAD 数量 {{ $value }}，需要人工检查或重放。"

      - alert: RocketMQConsumerBacklogHigh
        expr: |
          sum(
            rocketmq_consumer_ready_messages{
              topic="notification-send-topic",
              consumer_group="notification-worker-group"
            }
          ) > 100
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "通知消费组 RocketMQ 积压超过 100"
          description: "Broker ready messages={{ $value }}。"

      - alert: RocketMQMessageSentToDlq
        expr: |
          increase(
            rocketmq_send_to_dlq_messages_total{
              consumer_group="notification-worker-group"
            }[5m]
          ) > 0
        labels:
          severity: critical
        annotations:
          summary: "最近 5 分钟有通知消息进入 DLQ"
          description: "新增 DLQ 数量 {{ $value }}。"

  - name: notification-platform-channel
    rules:
      - alert: ChannelP95TooHigh
        expr: |
          histogram_quantile(
            0.95,
            sum by (le, provider) (
              rate(notification_channel_call_duration_seconds_bucket[5m])
            )
          ) > 2
        for: 1m
        labels:
          severity: warning
        annotations:
          summary: "渠道 {{ $labels.provider }} P95 超过 2 秒"
          description: "当前 P95={{ $value }} 秒。"

      - alert: ChannelCircuitOpen
        expr: notification_channel_circuit_state{state="open"} == 1
        for: 15s
        labels:
          severity: critical
        annotations:
          summary: "渠道 {{ $labels.provider }} 熔断器已打开"
          description: "channel={{ $labels.channel }} provider={{ $labels.provider }}。"

      - alert: ChannelExecutorQueueAlmostFull
        expr: |
          notification_channel_executor_queued
          /
          clamp_min(
            notification_channel_executor_queued
            + notification_channel_executor_queue_remaining,
            1
          ) > 0.8
        for: 1m
        labels:
          severity: warning
        annotations:
          summary: "渠道 {{ $labels.channel }} 线程池队列使用率超过 80%"
          description: "请检查渠道慢调用，不要直接盲目调大线程池。"
```

### 5.16 新增 `alertmanager.yml`

位置：`deploy/alertmanager/alertmanager.yml`

```yaml
global:
  resolve_timeout: 30s
  smtp_smarthost: mailhog:1025
  smtp_from: alertmanager@notification.local
  smtp_require_tls: false

route:
  receiver: local-mail
  group_by:
    - alertname
    - job
    - severity
  group_wait: 10s
  group_interval: 30s
  repeat_interval: 4h

receivers:
  - name: local-mail
    email_configs:
      - to: day26@notification.local
        send_resolved: true
```

### 5.17 新增 Grafana 数据源配置

位置：`deploy/grafana/provisioning/datasources/prometheus.yml`

```yaml
apiVersion: 1

datasources:
  - name: Prometheus
    uid: prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
    editable: false
```

### 5.18 新增 Grafana Dashboard provider

位置：`deploy/grafana/provisioning/dashboards/default.yml`

```yaml
apiVersion: 1

providers:
  - name: notification-platform
    orgId: 1
    folder: Notification Platform
    type: file
    disableDeletion: false
    allowUiUpdates: false
    updateIntervalSeconds: 10
    options:
      path: /var/lib/grafana/dashboards
```

### 5.19 新增 `notification-day26.json`

位置：`deploy/grafana/dashboards/notification-day26.json`

```json
{
  "annotations": {"list": []},
  "editable": false,
  "graphTooltip": 1,
  "id": null,
  "links": [],
  "panels": [
    {
      "datasource": {"type": "prometheus", "uid": "prometheus"},
      "fieldConfig": {"defaults": {"mappings": [], "thresholds": {"mode": "absolute", "steps": [{"color": "red", "value": null}, {"color": "green", "value": 1}]}}, "overrides": []},
      "gridPos": {"h": 6, "w": 6, "x": 0, "y": 0},
      "id": 1,
      "options": {"colorMode": "background", "graphMode": "area", "justifyMode": "auto", "orientation": "auto", "reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": false}, "textMode": "auto", "wideLayout": true},
      "targets": [{"expr": "min(up{job=~\"notification-server|notification-worker|rocketmq-broker\"})", "refId": "A"}],
      "title": "关键 Target 存活",
      "type": "stat"
    },
    {
      "datasource": {"type": "prometheus", "uid": "prometheus"},
      "fieldConfig": {"defaults": {"color": {"mode": "palette-classic"}, "custom": {"drawStyle": "line", "fillOpacity": 10, "lineWidth": 2, "showPoints": "never"}}, "overrides": []},
      "gridPos": {"h": 6, "w": 9, "x": 6, "y": 0},
      "id": 2,
      "options": {"legend": {"displayMode": "list", "placement": "bottom", "showLegend": true}, "tooltip": {"mode": "multi", "sort": "desc"}},
      "targets": [{"expr": "notification_outbox_backlog", "legendFormat": "{{status}}", "refId": "A"}],
      "title": "Outbox backlog",
      "type": "timeseries"
    },
    {
      "datasource": {"type": "prometheus", "uid": "prometheus"},
      "fieldConfig": {"defaults": {"unit": "s"}, "overrides": []},
      "gridPos": {"h": 6, "w": 9, "x": 15, "y": 0},
      "id": 3,
      "options": {"legend": {"displayMode": "list", "placement": "bottom", "showLegend": true}, "tooltip": {"mode": "single", "sort": "none"}},
      "targets": [{"expr": "notification_outbox_oldest_pending_age_seconds", "legendFormat": "oldest", "refId": "A"}],
      "title": "最老 Outbox 年龄",
      "type": "timeseries"
    },
    {
      "datasource": {"type": "prometheus", "uid": "prometheus"},
      "fieldConfig": {"defaults": {"mappings": [{"options": {"0": {"color": "red", "text": "UNTRUSTED"}, "1": {"color": "green", "text": "TRUSTED"}}, "type": "value"}]}, "overrides": []},
      "gridPos": {"h": 6, "w": 6, "x": 0, "y": 6},
      "id": 4,
      "options": {"colorMode": "background", "graphMode": "none", "justifyMode": "auto", "orientation": "auto", "reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": false}, "textMode": "auto", "wideLayout": true},
      "targets": [{"expr": "notification_shortlink_bloom_trusted", "refId": "A"}],
      "title": "Bloom 信任状态",
      "type": "stat"
    },
    {
      "datasource": {"type": "prometheus", "uid": "prometheus"},
      "fieldConfig": {"defaults": {}, "overrides": []},
      "gridPos": {"h": 6, "w": 9, "x": 6, "y": 6},
      "id": 5,
      "options": {"legend": {"displayMode": "list", "placement": "bottom", "showLegend": true}, "tooltip": {"mode": "multi", "sort": "desc"}},
      "targets": [
        {"expr": "sum(rocketmq_consumer_ready_messages{consumer_group=\"notification-worker-group\"})", "legendFormat": "ready", "refId": "A"},
        {"expr": "sum(rocketmq_consumer_inflight_messages{consumer_group=\"notification-worker-group\"})", "legendFormat": "inflight", "refId": "B"}
      ],
      "title": "RocketMQ ready / inflight",
      "type": "timeseries"
    },
    {
      "datasource": {"type": "prometheus", "uid": "prometheus"},
      "fieldConfig": {"defaults": {"unit": "s"}, "overrides": []},
      "gridPos": {"h": 6, "w": 9, "x": 15, "y": 6},
      "id": 6,
      "options": {"legend": {"displayMode": "list", "placement": "bottom", "showLegend": true}, "tooltip": {"mode": "multi", "sort": "desc"}},
      "targets": [{"expr": "histogram_quantile(0.95, sum by (le, provider) (rate(notification_channel_call_duration_seconds_bucket[5m])))", "legendFormat": "{{provider}}", "refId": "A"}],
      "title": "渠道调用 P95",
      "type": "timeseries"
    },
    {
      "datasource": {"type": "prometheus", "uid": "prometheus"},
      "fieldConfig": {"defaults": {}, "overrides": []},
      "gridPos": {"h": 7, "w": 12, "x": 0, "y": 12},
      "id": 7,
      "options": {"legend": {"displayMode": "table", "placement": "bottom", "showLegend": true}, "tooltip": {"mode": "multi", "sort": "desc"}},
      "targets": [{"expr": "notification_channel_circuit_state", "legendFormat": "{{channel}}/{{provider}}/{{state}}", "refId": "A"}],
      "title": "渠道熔断状态（one-hot）",
      "type": "timeseries"
    },
    {
      "datasource": {"type": "prometheus", "uid": "prometheus"},
      "fieldConfig": {"defaults": {}, "overrides": []},
      "gridPos": {"h": 7, "w": 12, "x": 12, "y": 12},
      "id": 8,
      "options": {"legend": {"displayMode": "list", "placement": "bottom", "showLegend": true}, "tooltip": {"mode": "multi", "sort": "desc"}},
      "targets": [
        {"expr": "rate(notification_mq_consume_total[1m])", "legendFormat": "consume {{kind}}", "refId": "A"},
        {"expr": "increase(notification_mq_dlq_received_total[5m])", "legendFormat": "dlq received / 5m", "refId": "B"}
      ],
      "title": "Worker 消费与 DLQ",
      "type": "timeseries"
    }
  ],
  "refresh": "5s",
  "schemaVersion": 39,
  "tags": ["notification", "day26", "reliability"],
  "templating": {"list": []},
  "time": {"from": "now-30m", "to": "now"},
  "timezone": "browser",
  "title": "Notification Platform - Day26",
  "uid": "notification-day26",
  "version": 1
}
```

### 5.20 修改 `deploy/docker-compose.yml`

在 Broker 的 `ports` 中增加：

```yaml
      - "5557:5557"
```

在 Prometheus 的 `volumes` 中增加规则目录，并声明 Alertmanager 依赖：

```yaml
  prometheus:
    # 保留当前 image、ports、command 等配置
    depends_on:
      - alertmanager
      - broker
    volumes:
      - ./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - ./prometheus/rules:/etc/prometheus/rules:ro
      - prometheus-data:/prometheus
```

在 `services:` 下新增：

```yaml
  alertmanager:
    image: prom/alertmanager:v0.28.1
    container_name: notification-alertmanager
    restart: unless-stopped
    ports:
      - "9093:9093"
    volumes:
      - ./alertmanager/alertmanager.yml:/etc/alertmanager/alertmanager.yml:ro
      - alertmanager-data:/alertmanager
    command:
      - --config.file=/etc/alertmanager/alertmanager.yml
      - --storage.path=/alertmanager
    depends_on:
      - mailhog
    networks:
      - notification-network

  grafana:
    image: grafana/grafana:12.2.0
    container_name: notification-grafana
    restart: unless-stopped
    ports:
      - "3000:3000"
    environment:
      GF_SECURITY_ADMIN_USER: admin
      GF_SECURITY_ADMIN_PASSWORD: notification123
      GF_USERS_ALLOW_SIGN_UP: "false"
    volumes:
      - ./grafana/provisioning:/etc/grafana/provisioning:ro
      - ./grafana/dashboards:/var/lib/grafana/dashboards:ro
      - grafana-data:/var/lib/grafana
    depends_on:
      - prometheus
    networks:
      - notification-network

  mailhog:
    image: mailhog/mailhog:v1.0.1
    container_name: notification-mailhog
    restart: unless-stopped
    ports:
      - "8025:8025"
    networks:
      - notification-network
```

在顶层 `volumes:` 增加：

```yaml
  alertmanager-data:
  grafana-data:
```

### 5.21 新增容量脚本 `notification-capacity.js`

位置：`performance/day26/notification-capacity.js`

```javascript
import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';

const baseUrl = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const tenantId = __ENV.TENANT_ID;

// 19 位 Java Long 不能转 JavaScript Number，始终按字符串发送。
const applicationId = __ENV.APPLICATION_ID;
const templateId = __ENV.TEMPLATE_ID;

if (!tenantId || !applicationId || !templateId) {
    throw new Error('必须传 TENANT_ID、APPLICATION_ID、TEMPLATE_ID');
}

const receiver = __ENV.RECEIVER || '13800138000';
const businessErrors = new Rate('task_create_business_errors');

export const options = {
    scenarios: {
        createNotificationTask: {
            executor: 'constant-arrival-rate',
            rate: Number(__ENV.RATE || 10),
            timeUnit: '1s',
            duration: __ENV.DURATION || '2m',
            preAllocatedVUs: Number(__ENV.PRE_ALLOCATED_VUS || 20),
            maxVUs: Number(__ENV.MAX_VUS || 200),
        },
    },
    thresholds: {
        task_create_business_errors: ['rate<0.01'],
        http_req_failed: ['rate<0.01'],
        'http_req_duration{endpoint:notification_task_create}': [
            'p(95)<500',
            'p(99)<1000',
        ],
    },
};

export default function () {
    const requestId = `day26-${Date.now()}-${__VU}-${__ITER}`;
    const payload = JSON.stringify({
        requestId,
        applicationId,
        templateId,
        recipients: [
            {
                receiver,
                params: {name: 'Day26'},
            },
        ],
    });

    const response = http.post(
        `${baseUrl}/api/v1/notification-tasks`,
        payload,
        {
            headers: {
                'Content-Type': 'application/json',
                'X-Tenant-Id': tenantId,
            },
            tags: {endpoint: 'notification_task_create'},
        }
    );

    const passed = check(response, {
        'status is 200': (result) => result.status === 200,
        'business code is success': (result) => {
            try {
                return result.json('code') === '000000';
            } catch (error) {
                return false;
            }
        },
    });

    businessErrors.add(!passed);
}
```

### 5.22 新增 Day26 报告模板

位置：`performance/day26/report.md`

```markdown
# Day26 容量、故障注入与告警闭环报告

## 1. 测试基线

- Git Commit：
- 日期与时区：
- 机器配置：
- Server / Worker 实例数：
- 关键限流、线程池、超时配置：

## 2. 容量实验

| 输入 RPS | HTTP P95/P99 | 错误率 | Outbox 峰值 | MQ ready 峰值 | 恢复到 0 耗时 | 首个饱和资源 |
|---:|---:|---:|---:|---:|---:|---|
| 5 | | | | | | |
| 10 | | | | | | |

## 3. 故障闭环

| 故障 | T0 注入 | T1 firing | T2 恢复 | T3 resolved/积压归零 | 业务降级 | 数据是否丢失 |
|---|---|---|---|---|---|---|
| Redis | | | | | | |
| RocketMQ Broker | | | | | | |
| MySQL | | | | | | |
| 渠道慢调用 | | | | | | |

## 4. 告警证据

- Prometheus Alerts 截图：
- Alertmanager firing 截图：
- MailHog FIRING 邮件截图：
- MailHog RESOLVED 邮件截图：
- Grafana 故障时间窗截图：

## 5. 结论

- 已验证稳定吞吐：
- 首个饱和点：
- 检测耗时：
- 恢复耗时：
- 建议容量余量：
- 仍未验证的边界：
```

## 六、实验验证

### 6.1 编译与单元测试

```bash
mvn -Dapi.version=1.44 \
  -pl notification-server,notification-worker \
  -am \
  -Dtest=OutboxMetricsTest,ShortLinkBloomMetricsTest,MqConsumeMetricsTest,ChannelMetricsTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

`-Dapi.version=1.44` 沿用本机 Docker 29 与当前 Testcontainers 的兼容参数。先重点确认三个新增测试和原有 `ChannelMetricsTest` 通过；完成 Day26 后再用同一参数执行完整模块回归。

### 6.2 校验 Docker、Prometheus 和 Alertmanager 配置

```bash
docker compose -f deploy/docker-compose.yml config

docker compose -f deploy/docker-compose.yml up -d

docker compose -f deploy/docker-compose.yml exec prometheus \
  promtool check config /etc/prometheus/prometheus.yml

docker compose -f deploy/docker-compose.yml exec prometheus \
  promtool check rules /etc/prometheus/rules/notification-alerts.yml

docker compose -f deploy/docker-compose.yml exec alertmanager \
  amtool check-config /etc/alertmanager/alertmanager.yml
```

任何配置校验失败都不能继续做故障实验。

### 6.3 启动应用并检查 Target

启动 Server：

```bash
mvn -pl notification-server -am spring-boot:run
```

启动 Worker：

```bash
mvn -pl notification-worker -am spring-boot:run
```

访问：

```text
Prometheus Targets  http://localhost:9090/targets
Prometheus Alerts   http://localhost:9090/alerts
Alertmanager        http://localhost:9093
Grafana             http://localhost:3000
MailHog             http://localhost:8025
RocketMQ metrics    http://localhost:5557/metrics
```

Grafana 登录：`admin / notification123`。启动后应自动出现 `Notification Platform - Day26`，不需要手工导入 JSON。

### 6.4 指标存在性检查

```bash
curl -s http://localhost:8080/actuator/prometheus \
  | grep 'notification_outbox'

curl -s http://localhost:8080/actuator/prometheus \
  | grep 'notification_shortlink_bloom_trusted'

curl -s http://localhost:8081/actuator/prometheus \
  | grep 'notification_channel'

curl -s http://localhost:5557/metrics \
  | grep 'rocketmq_consumer_ready_messages'
```

RocketMQ 部分指标只有 Topic、消费组和流量真实出现后才有时间序列，空环境查不到不等于配置失败。先发送一条正常通知再检查。

### 6.5 实验一：Redis 故障与 Bloom fail-open

记录 T0，然后停止 Redis：

```bash
docker compose -f deploy/docker-compose.yml stop redis
```

预期：

1. `notification_shortlink_bloom_trusted` 变成 0；
2. `ShortLinkBloomUntrusted` 先 pending，30 秒后 firing；
3. 合法短链不能被 Bloom 直接误杀，应尝试回源 MySQL；
4. Worker 限流按当前配置 fail-open，日志出现 `redis限流异常，执行fail-open`；
5. MySQL 压力风险上升，但数据正确性不应丢失。

恢复并记录 T2：

```bash
docker compose -f deploy/docker-compose.yml start redis
```

等待 `ShortLinkBloomInitializer` 完成重建：

```promql
notification_shortlink_bloom_trusted
```

回到 1 后，Prometheus/Alertmanager 应自动 resolved，MailHog 收到恢复邮件。记录 T3-T2。

### 6.6 实验二：RocketMQ Broker 故障与 Outbox 削峰

停止 Broker：

```bash
docker compose -f deploy/docker-compose.yml stop broker
```

继续创建 120 条通知任务。预期：

```text
HTTP 本地事务仍可成功
→ notify_outbox 保存 NEW/FAILED
→ Outbox backlog 增长
→ 最老年龄增长
→ rocketmq-broker target down
→ OutboxBacklogHigh / OutboxOldestEventTooOld firing
```

恢复：

```bash
docker compose -f deploy/docker-compose.yml start broker
```

观察：

```promql
notification_outbox_backlog{status="pending"}
rocketmq_consumer_ready_messages{consumer_group="notification-worker-group"}
```

验收不是“Broker 容器 Up”，而是 Outbox 和 MQ backlog 最终都回到 0，并出现 RESOLVED 邮件。这个自动恢复结论依赖本次把 Outbox 最大尝试次数提高到 20；如果已经出现 `DEAD`，当前调度器不会自动重放，必须单独执行人工恢复 Runbook，不能直接改数据库冒充成功。

### 6.7 实验三：MySQL 故障必须 fail-closed

```bash
docker compose -f deploy/docker-compose.yml stop mysql
```

预期：

- 创建任务失败，不能在无法落库时假装接收成功；
- Worker 无法确认幂等和状态，消息不得错误确认；
- `notification_outbox_metrics_refresh_success` 变成 0；
- `OutboxMetricsRefreshFailed` firing；
- 旧 backlog Gauge 保留，但 freshness 明确失败，不能误读旧值。

恢复：

```bash
docker compose -f deploy/docker-compose.yml start mysql
```

等待数据库 health、refresh success、消息重投和 backlog 全部恢复后再记录 T3。

### 6.8 实验四：渠道慢调用、超时与熔断

连续创建至少 3 条 SMS 通知，receiver 使用：

```text
provider-slow:mock-sms-primary:13800138000
```

当前 Mock 主渠道 sleep 5 秒，Worker 超时配置为 2 秒，因此预期：

```text
渠道 TIMEOUT
→ MQ 抛异常重投
→ failure threshold=3 后熔断 OPEN
→ notification_channel_circuit_state{state="open"}=1
→ ChannelCircuitOpen firing
```

这是“结果未知”，不能切换备用供应商。等待 `open-duration=10s` 后，再发送一条正常 SMS 作为 HALF_OPEN 探测；成功后 CLOSED=1、OPEN=0，告警 resolved。

### 6.9 实验五：DLQ 闭环

使用已有毒消息入口：

```text
exception-always:13800138000
```

等待 RocketMQ 重试耗尽。预期同时看到：

```text
rocketmq_send_to_dlq_messages_total 增长
notification_mq_dlq_received_total 增长
RocketMQMessageSentToDlq firing
notify_message 最终状态为 DEAD
notify_send_record 记录失败原因
```

只看到 DLQ Topic 有消息但业务状态没有落库，不能算闭环。

### 6.10 实验六：容量和恢复时间

先跑 5 RPS，再跑 10 RPS：

```bash
docker run --rm \
  -v "$PWD/performance/day26:/scripts" \
  -e TENANT_ID=你的租户ID \
  -e APPLICATION_ID=你的19位应用ID \
  -e TEMPLATE_ID=你的19位模板ID \
  -e RATE=5 \
  -e DURATION=2m \
  grafana/k6:0.54.0 \
  run /scripts/notification-capacity.js
```

再把 `RATE=10` 重跑。停止压力后不要立刻结束记录，持续观察：

```promql
notification_outbox_backlog{status="pending"}
rocketmq_consumer_ready_messages{consumer_group="notification-worker-group"}
notification_channel_executor_queued
hikaricp_connections_pending
```

如果结果与 Day14 一致，应看到 10 RPS 首先超过 5 token/s 限流补充速度，而 CPU、Hikari Pending 和渠道队列没有先饱和。报告必须填写 backlog 峰值和恢复到 0 的准确耗时。

### 6.11 验证告警自动 resolved

每个故障至少保存四类证据：

1. Prometheus rule 从 pending 到 firing；
2. Alertmanager 显示 active alert；
3. MailHog 收到 FIRING 邮件；
4. 恢复后 MailHog 收到 RESOLVED 邮件。

只截图 Grafana 红线不代表告警链路完成；只收到 firing、没有 resolved 也不算闭环。

### 6.12 最终验收清单

- [ ] Server、Worker、RocketMQ 三个 Target 为 UP；
- [ ] Grafana 数据源和 Dashboard 自动加载；
- [ ] Outbox backlog、oldest age 和 freshness 指标可查询；
- [ ] Bloom trusted 指标可在 Redis 故障/恢复时变成 0/1；
- [ ] RocketMQ ready、inflight、lag、DLQ 指标来自 Broker；
- [ ] Worker 初次消费、重试、失败耗时和 DLQ 指标有测试；
- [ ] Redis 故障时短链 fail-open，不误杀合法短链；
- [ ] MySQL 故障时业务 fail-closed；
- [ ] RocketMQ 故障时 Outbox 积压，恢复后自动收敛；
- [ ] 渠道慢调用能触发超时、熔断、恢复；
- [ ] 每个故障都有 firing 与 resolved 证据；
- [ ] 报告写明首个饱和点、峰值和恢复时间；
- [ ] 没有使用未经验证的“百万 QPS”；
- [ ] 没有虚构尚未生产启用的分片倾斜实时指标；
- [ ] 没有虚构 Day24/25 的 migration lag。

## 七、面试追问

### 7.1 Redis 挂了以后哪些功能应 fail-open，哪些应 fail-closed？

短链 Bloom 可 fail-open 回源 MySQL；当前通知限流可按业务选择 fail-open，但必须告警和限时；幂等、任务状态和资金/权限类正确性不能因 Redis 故障放开。判断标准是误放与误拒的业务成本。

### 7.2 为什么 Outbox 只看 backlog 数量不够？

10 条积压等待 30 分钟可能比 100 条等待 2 秒更严重。必须同时观察数量、最老年龄、流入/处理速率和恢复趋势。

### 7.3 为什么 Gauge 不应该在 Prometheus scrape 时直接查数据库？

抓取频率会直接变成数据库查询频率；监控并发和数据库故障可能拖慢指标接口。后台低频采样、内存暴露，并增加 freshness 指标更稳妥。

### 7.4 数据库故障时为什么保留旧 Gauge，还要增加 refresh success？

旧值能保留故障前上下文，但不能冒充实时值。`refresh_success=0` 和 `last_success_age` 告诉值班人员快照已经过期。

### 7.5 RocketMQ backlog 为什么必须从 Broker 获取？

Worker 只知道已拉取到本地的消息，不知道 Broker 最大 offset 与消费 offset 的差。ready、inflight 和 lag 必须由 Broker/Exporter 的 offset 真相计算。

### 7.6 ready messages、inflight messages 和 lag latency 有什么区别？

ready 是尚未拉取的数量；inflight 是已拉取但尚未确认的数量；lag latency 是最早未完成消息已经等待的时间。数量和时效需要一起看。

### 7.7 为什么 Counter 重启后变小不代表业务回滚？

Counter 是进程内累计值，进程重启会归零。PromQL 应使用 `rate()`/`increase()`，Prometheus 会处理时间序列重置。

### 7.8 为什么不能把 tenantId、messageId 放进指标标签？

它们取值无界，每个组合都会创建新时间序列，造成内存、磁盘和查询成本爆炸。高基数明细应进入日志、追踪或分析库。

### 7.9 `for: 2m` 的作用是什么？

表达式持续为真 2 分钟后才从 pending 进入 firing，用于过滤短暂毛刺。它不是采样窗口；窗口由 PromQL 中的 `[5m]` 等决定。

### 7.10 Prometheus 与 Alertmanager 分别负责什么？

Prometheus 采集、存储、计算规则并产生 alert；Alertmanager 负责分组、去重、静默、抑制、路由和发送 firing/resolved 通知。

### 7.11 Grafana 能否替代 Prometheus 告警？

Grafana 可以提供自己的统一告警能力，但本实验选择 Prometheus rule + Alertmanager，职责和配置链路更直观。Grafana 在这里主要负责可视化，避免同一条件维护两套规则。

### 7.12 渠道超时后为什么不能立刻切备用？

超时只表示本方没有及时拿到结果，主供应商可能已经受理。立即切备用可能重复触达；应使用同一幂等键重试，并根据供应商查询/回执确认。

### 7.13 熔断器 OPEN 后为什么仍需要 HALF_OPEN？

OPEN 只能暂时保护系统，无法证明下游已经恢复。等待冷却时间后放一个探测请求，成功才 CLOSED，失败重新 OPEN。

### 7.14 RocketMQ 挂了为什么 HTTP 创建任务仍可成功？

任务、消息和 Outbox 在 MySQL 本地事务中已经提交。MQ 是异步传输通道，恢复后 Outbox 重新发布；如果 MySQL 都没有落库则必须失败。

### 7.15 Outbox backlog 恢复为 0 是否证明没有丢消息？

不完全证明。还要核对 PUBLISHED/DEAD、消息终态、DLQ 和消费幂等记录。backlog=0 可能是全部成功，也可能是全部进入 DEAD。

### 7.16 如何定位首个饱和点？

逐级提高输入速率，同时对比 HTTP、CPU、连接池、队列、限流、Outbox、MQ 和渠道耗时。第一个持续达到上限并导致下游积压的资源才是首个饱和点，不能只凭相关性判断。

### 7.17 当前项目的首个饱和点是什么？

Day14 本地实测中是 5 token/s 渠道令牌桶；10 RPS 输入产生业务积压，而 CPU、Hikari Pending 和渠道队列没有先饱和。这个结论只适用于当时机器、配置和数据规模。

### 7.18 为什么恢复时间比“组件重新启动成功”更重要？

组件 Up 只说明开始恢复服务；历史积压可能仍影响用户。真正恢复点应定义为关键 SLI 回到目标范围、backlog 收敛且告警 resolved。

### 7.19 Day26 为什么不实现 migration lag？

Day24/25 尚未落地双写、回填和 checkpoint，没有真实数据源。提前注册常量 0 只会制造虚假安全感。等迁移代码存在后再从 checkpoint、补偿表和新旧路由版本计算。

### 7.20 如果面试官问 Day26 的真实成果，怎么回答？

可以回答：项目原来只有 Prometheus 抓取和一次性压测报告；Day26 增加了 Outbox/Bloom/MQ 关键指标、RocketMQ Broker 原生 metrics、Grafana 配置化 Dashboard、Prometheus rules、Alertmanager 与本地 firing/resolved 通知，并真实注入 Redis、RocketMQ、MySQL 和渠道慢调用故障，记录检测时间、降级行为、积压峰值和恢复时间。迁移 lag 因 Day24/25 尚未实施，明确没有虚构。
