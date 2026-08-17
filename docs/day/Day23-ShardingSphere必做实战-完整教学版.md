# Day23：ShardingSphere 必做实战（完整教学版）

> 真实代码基线：`3a4c4b2568a37562579510f7cee4416719dad7dd`
>
> 学习计划已对齐：原 30 天计划确定的“模块化单体 + 独立 Worker、共享 Schema 多租户、基础设施实现留在 infrastructure”边界，以及《多租户统一通知平台-补充学习计划》Day23。Day21 继续后移；Day22 已完成容量证明和纯 Java 路由模拟，Day23 才进入真实 ShardingSphere 路由实验。
>
> 本文只新增教学文档，不直接修改项目业务源码。第五部分给出的代码，是你学习 Day23 时基于当前仓库手动完成的完整增量。PoC 使用两个真实 MySQL 容器、每库四张发送记录表；ShardingSphere 依赖仅放在 `test` scope，不会把 Server/Worker 默认数据源提前切到新分片。Day24 才处理在线双写与回填。

## 一、原理

### 1.1 ShardingSphere-JDBC 在当前项目里处于哪一层

ShardingSphere-JDBC 不是新的数据库，也不是远程代理。它在应用进程内实现标准 JDBC `DataSource`，位于 MyBatis-Plus 与真实 MySQL 数据源之间：

```text
业务服务 / Repository
        ↓
MyBatis-Plus + 多租户拦截器
        ↓ 逻辑 SQL：notify_send_record
ShardingSphere-JDBC
        ├── SQL 解析
        ├── 分片路由
        ├── SQL 改写
        ├── 多节点执行
        └── 结果归并
        ↓
ds_0.notify_send_record_0 ... notify_send_record_3
ds_1.notify_send_record_0 ... notify_send_record_3
```

应用仍然写逻辑表名 `notify_send_record`。ShardingSphere 根据 SQL 中的 `message_id`，把逻辑 SQL 改写为物理表 SQL。

Apache 官方文档将核心过程概括为解析、路由、改写、执行和归并；YAML 创建出的对象仍实现标准 JDBC `DataSource`：

- [ShardingSphere 分片内核](https://shardingsphere.apache.org/document/5.5.2/en/reference/sharding/)
- [ShardingSphere-JDBC YAML 配置](https://shardingsphere.apache.org/document/5.5.1/en/user-manual/shardingsphere-jdbc/yaml-config/)

这意味着它可以透明掉“选择哪个库、改写成哪张物理表、合并结果”等机械工作，但无法替业务决定分片键、租户隔离、事务边界、扩容流程和跨分片查询是否合理。

### 1.2 逻辑表、物理表与数据节点

Day23 选择 Day22 已论证的高增长附属表：

```text
逻辑表：notify_send_record
分片键：message_id
```

PoC 规模：

```text
2 个库 × 每库 4 张表 = 8 个物理数据节点
```

物理节点为：

```text
ds_0.notify_send_record_0
ds_0.notify_send_record_1
ds_0.notify_send_record_2
ds_0.notify_send_record_3
ds_1.notify_send_record_0
ds_1.notify_send_record_1
ds_1.notify_send_record_2
ds_1.notify_send_record_3
```

`actualDataNodes` 只是在配置中声明所有合法节点；它不会自动创建数据库或物理表。因此测试会先用两个 MySQL Testcontainers 创建真实 DDL，再初始化 ShardingSphere。

### 1.3 为什么继续使用 Day22 的独立位段

2 库需要 1 bit，4 表需要 2 bit。对正数 `message_id` 使用不重叠位段：

```text
tableIndex = message_id & 3
dbIndex    = (message_id >> 2) & 1
```

例子：

| messageId | 二进制低 3 位 | dbIndex | tableIndex | 目标节点 |
|---:|---:|---:|---:|---|
| 0 | `000` | 0 | 0 | `ds_0.table_0` |
| 1 | `001` | 0 | 1 | `ds_0.table_1` |
| 2 | `010` | 0 | 2 | `ds_0.table_2` |
| 3 | `011` | 0 | 3 | `ds_0.table_3` |
| 4 | `100` | 1 | 0 | `ds_1.table_0` |
| 5 | `101` | 1 | 1 | `ds_1.table_1` |
| 6 | `110` | 1 | 2 | `ds_1.table_2` |
| 7 | `111` | 1 | 3 | `ds_1.table_3` |

八个连续 ID 恰好命中八个节点。与错误公式比较：

```text
错误：dbIndex = message_id % 2
错误：tableIndex = message_id % 4
```

此时 `tableIndex % 2 == dbIndex`，八个物理节点只有四个可达。Day23 会用第二份错误 YAML 在真实 ShardingSphere 环境中复现，而不是只停留在 Day22 的纯 Java 模拟器。

### 1.4 标准分片策略能处理什么

Day23 使用 `standard` 策略和单一分片列 `message_id`。内置 `INLINE` 算法适合简单表达式：

```yaml
databaseStrategy:
  standard:
    shardingColumn: message_id
    shardingAlgorithmName: send-record-database-inline
```

等值和 `IN` 条件可精确路由：

```sql
WHERE message_id = ?
WHERE message_id IN (?, ?, ?)
```

范围条件无法从取模/位段公式推导出少量连续节点，因此本实验显式启用：

```yaml
allow-range-query-with-inline-sharding: true
```

它的含义不是“范围路由变快”，而是允许范围 SQL 并进行全路由。官方算法文档也明确说明，Inline 范围查询开启后会忽略精确路由并访问全部节点：

- [内置分片算法](https://shardingsphere.apache.org/document/5.2.0/en/user-manual/common-config/builtin-algorithm/sharding/)

### 1.5 缺少分片键为什么危险

当前发送完成 SQL 只有：

```sql
WHERE id = ?
  AND send_status = 'PROCESSING'
```

但 Day23 的路由键是 `message_id`。ShardingSphere 无法从 `id` 反推出 `message_id`，只能把 UPDATE 改写到八个节点。

即使 `id` 全局唯一，广播 UPDATE 仍有三个问题：

1. 每次完成发送都占用八个节点的连接和执行资源；
2. 节点数扩展后成本线性放大；
3. 错误数据或 ID 冲突会扩大更新范围。

因此 Day23 必须把接口改成：

```java
markSuccess(id, messageId, providerMessageId, finishedAt)
```

物理执行时既有 ShardingSphere 所需的 `message_id`，又有 MyBatis 多租户拦截器追加的 `tenant_id`：

```sql
WHERE id = ?
  AND message_id = ?
  AND send_status = 'PROCESSING'
  AND tenant_id = ?
```

这是路由条件、业务主键和租户边界三层约束，职责不能互相替代。

### 1.6 分布式主键与当前 `ASSIGN_ID`

分片后不能让八张物理表各自使用从 1 开始的自增 ID，否则逻辑表范围内会重复。当前 `SendRecordDO` 已使用：

```java
@TableId(type = IdType.ASSIGN_ID)
```

也就是 MyBatis-Plus 在进入数据库前生成 ID。它已经是一种应用侧全局 ID 方案，生产代码不需要为了展示 ShardingSphere 而强行替换。

不过补充计划要求理解 ShardingSphere 分布式主键，因此 PoC 仍配置：

```yaml
keyGenerateStrategy:
  column: id
  keyGeneratorName: snowflake
```

测试用原生 JDBC 刻意省略 `id`，验证 ShardingSphere 能生成全局 ID；真实 Repository 仍提交自己的 `ASSIGN_ID`。这也说明：

```text
配置了 keyGenerateStrategy
≠ 每条 INSERT 一定由 ShardingSphere 生成 ID
```

如果上游已经提供 ID，ShardingSphere不会再覆盖它。官方文档还提醒：Snowflake ID 直接参与 `2^n` 取模时可能出现低位分布问题，因此本实验把 `max-vibration-offset` 设为 `8 - 1 = 7`：

- [ShardingSphere 分布式主键算法](https://shardingsphere.apache.org/document/5.5.1/en/user-manual/common-config/builtin-algorithm/keygen/)

### 1.7 广播表与绑定表不是一回事

广播表适合体积小、变化少、每个库都需要一份完整副本的数据。Day23 使用真实的 `sys_tenant` 作为实验对象：

```text
逻辑 INSERT sys_tenant
→ ds_0.sys_tenant
→ ds_1.sys_tenant
```

它不意味着租户表永远必须广播；这是 PoC 用来学习复制语义。生产环境还要考虑变更一致性、DDL、删除、审计和故障恢复。

绑定表用于具有相同分片关系的主从表。Day23 把：

```text
notify_message.id
notify_send_record.message_id
```

按同一位段规则放到同一库、同一表后缀，并声明：

```yaml
bindingTables:
  - notify_message,notify_send_record
```

这样带 `message.id = send_record.message_id` 且包含分片值的 JOIN 可以只命中一个对应节点组合，而不必把所有物理表做笛卡尔路由。

注意：写上 `bindingTables` 不会自动让数据共址。两个表的分片算法、节点拓扑和 JOIN 条件必须真正一致。

### 1.8 跨分片分页和聚合为什么“能执行但不等于便宜”

逻辑 SQL：

```sql
SELECT *
FROM notify_send_record
WHERE tenant_id = ?
ORDER BY message_id
LIMIT 5 OFFSET 10000;
```

缺少分片键时，ShardingSphere 需要让多个节点返回候选集，再做全局归并。OFFSET 越大，每个节点参与排序和传输的候选行越多。

聚合也有类似过程：

```text
各节点局部 COUNT/GROUP BY
        ↓
ShardingSphere 二次归并
        ↓
逻辑结果
```

所以本课既验证结果正确，也要求观察实际 SQL 数量。生产治理通常需要：

- 尽量带分片键查询；
- 用游标/seek 分页代替大 OFFSET；
- 把高频全局统计异步汇总到统计表或 OLAP；
- 对无分片键的 UPDATE/DELETE 增加 SQL 审计或应用保护。

### 1.9 本地事务、XA 与业务一致性

默认 JDBC 本地事务可以验证：

- 单数据源事务提交和回滚；
- 因业务异常触发的跨库回滚。

但它无法保证跨库提交过程中发生网络、进程或硬件故障时的原子性。XA/BASE 也各有成本和限制。官方文档说明 ShardingSphere 支持 LOCAL、XA 和 BASE 类型，但使用分布式事务并不等于消除 CAP 取舍：

- [ShardingSphere-JDBC 事务 API](https://shardingsphere.apache.org/document/current/en/user-manual/shardingsphere-jdbc/special-api/transaction/)
- [事务能力与限制](https://shardingsphere.apache.org/document/5.3.0/en/features/transaction/limitations/)

当前通知发送本来就以 Outbox、MQ 幂等和状态机实现最终一致性。Day23 不为了一个 PoC 引入 XA；测试只验证显式业务异常时的回滚，并把“提交阶段故障不保证原子”写入结论。

### 1.10 为什么固定使用 5.5.1，而不是随手追最新版

知识星球 `short_link/pom.xml` 当前真实使用 ShardingSphere `5.5.1`，本课为了做同版本对照也固定到 `5.5.1`。这不是宣称它永远是最佳生产版本。

依赖升级应单独验证：

- YAML 规则结构和可选 SPI 是否变化；
- Spring Boot、SnakeYAML、HikariCP 和 MySQL 驱动兼容性；
- SQL 解析、事务和分布式主键行为；
- 官方发布说明和安全公告。

截至本文编写时，Apache 下载页已经提供更新版本，但课程基线不能在没有回归测试的情况下自动漂移。

## 二、现有数据流

### 2.1 当前数据源链路

Server 与 Worker 都直接连接单库：

```text
application.yml
  spring.datasource.url
        ↓
com.mysql.cj.jdbc.Driver
        ↓
notification_platform
        ↓
notify_send_record
```

当前没有 ShardingSphere 依赖、分片 YAML 或物理分片表。Flyway 由 Server 对单库执行，Worker 关闭 Flyway。

### 2.2 当前发送记录写入链路

真实代码链路：

```text
NotificationSendListener
        ↓
NotificationSendOrchestrator
        ↓
MessageSendTransactionService.prepare
        ├── 查询 notify_message
        ├── QUEUED → SENDING
        └── SendRecordRepository.save
                ↓
        SendRecordRepositoryImpl
                ↓
        SendRecordMapper.insert
                ↓
        notify_send_record
```

`SendRecordDO.id` 使用 `IdType.ASSIGN_ID`；`tenant_id` 由 `TenantMetaObjectHandler` 从 `TenantContext` 填充。

### 2.3 当前查询链路

MQ 重投或 DLQ 处理时按：

```sql
message_id = ? AND attempt_no = ?
```

查询发送记录：

```text
MessageSendTransactionService
        ↓
SendRecordRepository.findByMessageIdAndAttemptNo
        ↓
MyBatis TenantLineInnerInterceptor 追加 tenant_id
        ↓
notify_send_record 单表查询
```

这一查询天然携带 Day23 分片键，接入后可以单分片路由。

### 2.4 当前完成发送链路存在的分片前置问题

成功：

```text
finishSuccess
  → markSuccess(sendRecordId, providerMessageId, finishedAt)
```

失败：

```text
finishFailure / finishDeadLetter
  → markFailed(sendRecordId, failureCode, failureReason, finishedAt)
```

真实 Mapper 只按 `id` 更新。单库中没有问题；按 `message_id` 分片后会广播到八个节点。这不是 ShardingSphere 配置能自动修复的业务 SQL 问题。

### 2.5 当前租户隔离链路

```text
HTTP Filter / MQ Listener
        ↓
TenantContext.setTenantId
        ↓
MyBatis TenantLineInnerInterceptor
        ↓
SQL 自动追加 tenant_id = 当前租户
```

ShardingSphere 不理解 `TenantContext`，也不会替代 MyBatis 多租户拦截器。正确顺序是 MyBatis 先形成包含租户条件的逻辑 SQL，再由 ShardingSphere 路由。

### 2.6 知识星球 `short_link` 的真实参考

知识星球项目当前真实链路为：

```text
Spring Data JPA
        ↓
org.apache.shardingsphere.driver.ShardingSphereDriver
        ↓
jdbc:shardingsphere:classpath:sharding.yaml
        ↓
32 库 × 256 表配置
```

值得复用的是：

- 使用标准 JDBC Driver 接入，不侵入 Repository API；
- 用 YAML 描述逻辑表和物理节点；
- 开启 `sql-show` 观察真实路由。

不能照搬的是：

- 32 × 256 的过大规模；
- 明文远程地址与密码；
- 未完成的迁移/双写配置；
- 只看 YAML 而没有自动化验证；
- 把 ShardingSphere 当成租户隔离或在线扩容的完整答案。

## 三、本次需要改动的数据流

### 3.1 Day23 的范围边界

本次分成两条数据流：

```text
生产默认链路：仍然连接单库 notify_send_record
实验测试链路：连接 2 库 × 4 表 ShardingSphere PoC
```

原因是 Day23 的任务是证明真实路由行为，不是无停机迁移存量数据。若现在直接把 Worker 默认数据源切换到空分片库，会让旧发送记录不可见；Day24 的双写、回填和 checkpoint 将失去意义。

### 3.2 生产代码需要提前修正的路由条件

修改前：

```text
finishSuccess / finishFailure
        ↓
UPDATE ... WHERE id = ?
        ↓
未来会广播 8 个节点
```

修改后：

```text
PreparedSend.messageId
        ↓
markSuccess(id, messageId, ...)
        ↓
UPDATE ... WHERE id = ? AND message_id = ? AND tenant_id = ?
        ↓
ShardingSphere 精确路由 1 个节点
```

`PreparedSend` 已经包含 `messageId`，不需要新增字段。

### 3.3 PoC 写入数据流

```text
JUnit Test
        ↓
逻辑 INSERT notify_send_record
        ↓
ShardingSphere Snowflake（测试省略 id 时）
        ↓
message_id 独立位段路由
        ↓
1 个 MySQL 物理节点
```

真实 Repository 写入时仍由 MyBatis-Plus `ASSIGN_ID` 生成 ID，ShardingSphere只负责路由。

### 3.4 PoC 查询数据流

单键：

```text
WHERE tenant_id = ? AND message_id = ?
        ↓
1 个物理节点
```

范围/分页/聚合：

```text
缺少等值分片键或使用范围条件
        ↓
8 个物理节点
        ↓
结果归并
```

实验会同时断言逻辑结果与物理分布，并通过 `sql-show` 输出观察执行 SQL。

### 3.5 绑定表 JOIN 数据流

```text
notify_message.id = messageId
notify_send_record.message_id = messageId
        ↓ 相同位段规则
同一 ds、同一 table 后缀
        ↓
单节点 JOIN
```

Day23 只为 PoC 创建最小 `notify_message_0..3` 物理表，不把生产 `notify_message` 正式迁移到分片环境。

### 3.6 广播表数据流

```text
INSERT sys_tenant
        ↓
!BROADCAST
        ├── ds_0.sys_tenant
        └── ds_1.sys_tenant
```

查询可从任一副本路由；修改会分发到所有数据源。实验结束后容器销毁，不影响单库租户数据。

### 3.7 事务数据流

```text
Connection.setAutoCommit(false)
        ├── INSERT → ds_0
        └── INSERT → ds_1
业务异常
        ↓
rollback
        ↓
两个节点均无数据
```

这只证明业务异常路径的本地事务回滚，不证明提交过程中任一数据库宕机仍原子。

## 四、文件位置（复用 / 新增 / 修改）

### 4.1 复用：当前真实代码

| 文件 | 复用原因 |
|---|---|
| `notification-core/src/main/java/com/tam/notification/domain/send/SendRecord.java` | 真实发送记录领域对象，不新增实验领域模型 |
| `notification-infrastructure/src/main/java/com/tam/notification/persistence/entity/SendRecordDO.java` | 保留 `IdType.ASSIGN_ID` 生产主键策略 |
| `notification-infrastructure/src/main/java/com/tam/notification/persistence/repository/SendRecordRepositoryImpl.java` | 真实 Repository 转换与持久化入口 |
| `notification-infrastructure/src/main/java/com/tam/notification/config/MybatisPlusConfig.java` | 保留 `tenant_id` 自动过滤 |
| `notification-infrastructure/src/main/java/com/tam/notification/persistence/handler/TenantMetaObjectHandler.java` | 保留新增记录的租户填充 |
| `notification-infrastructure/src/main/resources/db/migration/V2__init_notification_task.sql` | PoC 的 `notify_message` DDL 来源 |
| `notification-infrastructure/src/main/resources/db/migration/V5__init_send_record.sql` | PoC 的发送记录 DDL 来源 |
| `notification-infrastructure/src/test/java/com/tam/notification/sharding/ShardRoutingSimulator.java` | Day22 独立位段算法的数学依据 |
| `notification-worker/src/main/java/com/tam/notification/model/PreparedSend.java` | 已有 `messageId`，用于完成更新时携带分片键 |

### 4.2 复用：知识星球项目只读参考

| 文件 | 只读参考内容 |
|---|---|
| `/Users/hingfaattam/workspace/learn_workspace/short_link/pom.xml` | ShardingSphere `5.5.1` 依赖基线 |
| `/Users/hingfaattam/workspace/learn_workspace/short_link/src/main/resources/application.yml` | `ShardingSphereDriver` 接入形式 |
| `/Users/hingfaattam/workspace/learn_workspace/short_link/src/main/resources/sharding.yaml` | 数据源、逻辑表、算法、`sql-show` 结构 |
| `/Users/hingfaattam/workspace/learn_workspace/short_link/src/main/resources/sharding-new.yaml` | 旧错误取模规则的反例 |

### 4.3 修改：学习时需要改动

| 文件 | 修改内容 |
|---|---|
| `pom.xml` | 增加 ShardingSphere 版本和依赖管理 |
| `notification-infrastructure/pom.xml` | 仅以 `test` scope 引入 ShardingSphere-JDBC |
| `notification-core/src/main/java/com/tam/notification/domain/send/SendRecordRepository.java` | 成功/失败更新增加 `messageId` |
| `notification-infrastructure/src/main/java/com/tam/notification/persistence/mapper/SendRecordMapper.java` | UPDATE 增加 `message_id` 路由条件 |
| `notification-infrastructure/src/main/java/com/tam/notification/persistence/repository/SendRecordRepositoryImpl.java` | 传递新的分片键参数 |
| `notification-worker/src/main/java/com/tam/notification/service/MessageSendTransactionService.java` | 从 `PreparedSend`/`SendRecord` 传入 `messageId` |

### 4.4 新增：Day23 PoC

| 文件 | 用途 |
|---|---|
| `notification-infrastructure/src/test/resources/day23/sharding-day23.yaml` | 正确的 2 库 × 4 表、绑定表、广播表和主键配置 |
| `notification-infrastructure/src/test/resources/day23/sharding-day23-broken.yaml` | 真实复现库表取模相关错误 |
| `notification-infrastructure/src/test/java/com/tam/notification/sharding/Day23PhysicalSchema.java` | 创建/清理/检查两个 MySQL 的物理表 |
| `notification-infrastructure/src/test/java/com/tam/notification/sharding/ShardingSphereDay23IntegrationTest.java` | CRUD、租户、范围、分页、聚合、事务、绑定表、广播表、全局 ID 验证 |

### 4.5 明确不修改

Day23 不修改：

- `notification-server/src/main/resources/application.yml`；
- `notification-worker/src/main/resources/application.yml`；
- `deploy/docker-compose.yml` 的默认 MySQL；
- Flyway 生产迁移脚本；
- `short_link` 任何源码或配置。

## 五、基于现有代码的完整增量代码

### 5.1 修改根 `pom.xml`

在 `<properties>` 中增加：

```xml
<!--
  Day23 固定使用知识星球 short_link 的 5.5.1 做可比实验。
  升级版本必须单独核对 YAML、SPI 和 Spring Boot 兼容性，不能自动漂移。
-->
<shardingsphere.version>5.5.1</shardingsphere.version>
```

在根 `<dependencyManagement><dependencies>` 中增加：

```xml
<!-- ShardingSphere 只由需要的模块声明；版本在父 POM 统一管理。 -->
<dependency>
    <groupId>org.apache.shardingsphere</groupId>
    <artifactId>shardingsphere-jdbc</artifactId>
    <version>${shardingsphere.version}</version>
</dependency>
```

### 5.2 修改 `notification-infrastructure/pom.xml`

在测试依赖区域增加：

```xml
<!--
  Day23 只在集成测试中创建 ShardingSphere DataSource。
  test scope 保证 Server/Worker 的默认运行时仍连接现有单库。
-->
<dependency>
    <groupId>org.apache.shardingsphere</groupId>
    <artifactId>shardingsphere-jdbc</artifactId>
    <scope>test</scope>
</dependency>
```

现有 `testcontainers`、`mysql`、`junit-jupiter` 和 MySQL Driver 已足够，不重复添加。

### 5.3 修改 `SendRecordRepository.java`

完整文件：

```java
package com.tam.notification.domain.send;

import java.time.LocalDateTime;
import java.util.Optional;

public interface SendRecordRepository {

    SendRecord save(SendRecord sendRecord);

    Optional<SendRecord> findByMessageIdAndAttemptNo(
            Long messageId,
            Integer attemptNo
    );

    /**
     * 完成成功发送。
     *
     * @param id                发送记录全局 ID，用于精确定位业务行
     * @param messageId         Day23 分片键，保证未来接入 ShardingSphere 后单节点路由
     * @param providerMessageId 渠道侧消息 ID
     * @param finishedAt        完成时间
     */
    boolean markSuccess(
            Long id,
            Long messageId,
            String providerMessageId,
            LocalDateTime finishedAt
    );

    /**
     * 完成失败发送。
     *
     * messageId 不是冗余参数：没有它时，按 message_id 分片的 UPDATE
     * 无法精确路由，只能广播到全部物理节点。
     */
    boolean markFailed(
            Long id,
            Long messageId,
            String failureCode,
            String failureReason,
            LocalDateTime finishedAt
    );
}
```

### 5.4 修改 `SendRecordMapper.java`

完整文件：

```java
package com.tam.notification.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tam.notification.persistence.entity.SendRecordDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface SendRecordMapper extends BaseMapper<SendRecordDO> {

    @Update("""
            UPDATE notify_send_record
            SET
                send_status = 'SUCCESS',
                provider_message_id = #{providerMessageId},
                finished_at = #{finishedAt}
            WHERE id = #{id}
              AND message_id = #{messageId}
              AND send_status = 'PROCESSING'
            """)
    int markSuccess(
            @Param("id") Long id,
            @Param("messageId") Long messageId,
            @Param("providerMessageId") String providerMessageId,
            @Param("finishedAt") LocalDateTime finishedAt
    );

    @Update("""
            UPDATE notify_send_record
            SET
                send_status = 'FAILED',
                failure_code = #{failureCode},
                failure_reason = #{failureReason},
                finished_at = #{finishedAt}
            WHERE id = #{id}
              AND message_id = #{messageId}
              AND send_status = 'PROCESSING'
            """)
    int markFailed(
            @Param("id") Long id,
            @Param("messageId") Long messageId,
            @Param("failureCode") String failureCode,
            @Param("failureReason") String failureReason,
            @Param("finishedAt") LocalDateTime finishedAt
    );
}
```

`TenantLineInnerInterceptor` 仍会追加 `tenant_id`，无需在注解 SQL 中从调用方重复传租户 ID。

### 5.5 修改 `SendRecordRepositoryImpl.java`

只替换两个完成方法：

```java
@Override
public boolean markSuccess(
        Long id,
        Long messageId,
        String providerMessageId,
        LocalDateTime finishedAt
) {
    return sendRecordMapper.markSuccess(
            id,
            messageId,
            providerMessageId,
            finishedAt
    ) == 1;
}

@Override
public boolean markFailed(
        Long id,
        Long messageId,
        String failureCode,
        String failureReason,
        LocalDateTime finishedAt
) {
    return sendRecordMapper.markFailed(
            id,
            messageId,
            failureCode,
            failureReason,
            finishedAt
    ) == 1;
}
```

其他转换、保存和查询代码保持不变。

### 5.6 修改 `MessageSendTransactionService.java`

`finishSuccess` 中替换为：

```java
boolean success = sendRecordRepository.markSuccess(
        prepared.sendRecordId(),
        prepared.messageId(), // 分片键必须沿调用链传到 UPDATE。
        result.providerMessageId(),
        LocalDateTime.now()
);
```

`finishFailure` 中替换为：

```java
boolean success = sendRecordRepository.markFailed(
        prepared.sendRecordId(),
        prepared.messageId(), // 避免未来按 message_id 分片后广播更新。
        result.errorCode(),
        result.errorMessage(),
        LocalDateTime.now()
);
```

`finishDeadLetter` 中替换为：

```java
boolean recordUpdated = sendRecordRepository.markFailed(
        sendRecord.getId(),
        sendRecord.getMessageId(), // DLQ 已查到完整记录，可直接取得分片键。
        failureCode,
        failureReason,
        LocalDateTime.now()
);
```

### 5.7 新增正确配置 `sharding-day23.yaml`

路径：

```text
notification-infrastructure/src/test/resources/day23/sharding-day23.yaml
```

完整内容：

```yaml
databaseName: notification_day23

dataSources:
  ds_0:
    dataSourceClassName: com.zaxxer.hikari.HikariDataSource
    driverClassName: com.mysql.cj.jdbc.Driver
    jdbcUrl: $${day23.ds0.jdbc-url::}
    username: $${day23.ds0.username::}
    password: $${day23.ds0.password::}
    maximumPoolSize: 4
  ds_1:
    dataSourceClassName: com.zaxxer.hikari.HikariDataSource
    driverClassName: com.mysql.cj.jdbc.Driver
    jdbcUrl: $${day23.ds1.jdbc-url::}
    username: $${day23.ds1.username::}
    password: $${day23.ds1.password::}
    maximumPoolSize: 4

rules:
  - !SHARDING
    tables:
      # notify_message 只为绑定表实验建立最小 PoC，不代表本日正式迁移该表。
      notify_message:
        actualDataNodes: ds_$->{0..1}.notify_message_$->{0..3}
        databaseStrategy:
          standard:
            shardingColumn: id
            shardingAlgorithmName: message-database-inline
        tableStrategy:
          standard:
            shardingColumn: id
            shardingAlgorithmName: message-table-inline
        keyGenerateStrategy:
          column: id
          keyGeneratorName: day23-snowflake

      notify_send_record:
        actualDataNodes: ds_$->{0..1}.notify_send_record_$->{0..3}
        databaseStrategy:
          standard:
            shardingColumn: message_id
            shardingAlgorithmName: send-record-database-inline
        tableStrategy:
          standard:
            shardingColumn: message_id
            shardingAlgorithmName: send-record-table-inline
        keyGenerateStrategy:
          column: id
          keyGeneratorName: day23-snowflake

    # 两张表必须使用同一 messageId 的相同库/表位段，绑定声明才成立。
    bindingTables:
      - notify_message,notify_send_record

    shardingAlgorithms:
      message-database-inline:
        type: INLINE
        props:
          algorithm-expression: 'ds_$->{(id >> 2) & 1}'
          allow-range-query-with-inline-sharding: true
      message-table-inline:
        type: INLINE
        props:
          algorithm-expression: 'notify_message_$->{id & 3}'
          allow-range-query-with-inline-sharding: true
      send-record-database-inline:
        type: INLINE
        props:
          algorithm-expression: 'ds_$->{(message_id >> 2) & 1}'
          allow-range-query-with-inline-sharding: true
      send-record-table-inline:
        type: INLINE
        props:
          algorithm-expression: 'notify_send_record_$->{message_id & 3}'
          allow-range-query-with-inline-sharding: true

    keyGenerators:
      day23-snowflake:
        type: SNOWFLAKE
        props:
          # 单机 PoC 固定 worker-id；多实例生产环境必须保证互斥分配。
          worker-id: 23
          # 总物理节点为 8，按官方建议配置为 2^3 - 1。
          max-vibration-offset: 7

  # 5.5.1 的广播表是独立规则，不再写在 !SHARDING 内。
  - !BROADCAST
    tables:
      - sys_tenant

props:
  # Day23 必须观察逻辑 SQL 与 Actual SQL，不能只看断言结果。
  sql-show: true
  sql-simple: false
```

`$${...}` 是 ShardingSphere JDBC URL 的动态占位符；测试连接 URL 必须带：

```text
?placeholder-type=system_props
```

### 5.8 新增错误配置 `sharding-day23-broken.yaml`

路径：

```text
notification-infrastructure/src/test/resources/day23/sharding-day23-broken.yaml
```

完整内容：

```yaml
databaseName: notification_day23_broken

dataSources:
  ds_0:
    dataSourceClassName: com.zaxxer.hikari.HikariDataSource
    driverClassName: com.mysql.cj.jdbc.Driver
    jdbcUrl: $${day23.ds0.jdbc-url::}
    username: $${day23.ds0.username::}
    password: $${day23.ds0.password::}
    maximumPoolSize: 4
  ds_1:
    dataSourceClassName: com.zaxxer.hikari.HikariDataSource
    driverClassName: com.mysql.cj.jdbc.Driver
    jdbcUrl: $${day23.ds1.jdbc-url::}
    username: $${day23.ds1.username::}
    password: $${day23.ds1.password::}
    maximumPoolSize: 4

rules:
  - !SHARDING
    tables:
      notify_send_record:
        actualDataNodes: ds_$->{0..1}.notify_send_record_$->{0..3}
        databaseStrategy:
          standard:
            shardingColumn: message_id
            shardingAlgorithmName: broken-database-inline
        tableStrategy:
          standard:
            shardingColumn: message_id
            shardingAlgorithmName: broken-table-inline
        keyGenerateStrategy:
          column: id
          keyGeneratorName: day23-snowflake

    shardingAlgorithms:
      # 错误：库索引和表索引使用同一值直接取模，二者存在确定相关性。
      broken-database-inline:
        type: INLINE
        props:
          algorithm-expression: 'ds_$->{message_id % 2}'
      broken-table-inline:
        type: INLINE
        props:
          algorithm-expression: 'notify_send_record_$->{message_id % 4}'

    keyGenerators:
      day23-snowflake:
        type: SNOWFLAKE
        props:
          worker-id: 24
          max-vibration-offset: 7

props:
  sql-show: true
  sql-simple: false
```

### 5.9 新增 `Day23PhysicalSchema.java`

路径：

```text
notification-infrastructure/src/test/java/com/tam/notification/sharding/Day23PhysicalSchema.java
```

完整代码：

```java
package com.tam.notification.sharding;

import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Day23 物理库辅助类。
 *
 * actualDataNodes 只描述节点，不负责建表；测试必须显式创建真实物理表。
 * 所有 SQL 只作用于 Testcontainers，测试结束后容器会被销毁。
 */
final class Day23PhysicalSchema {

    static final int DATABASE_COUNT = 2;
    static final int TABLE_COUNT_PER_DATABASE = 4;

    private Day23PhysicalSchema() {
    }

    static void create(MySQLContainer<?> container) throws SQLException {
        try (Connection connection = connection(container);
             Statement statement = connection.createStatement()) {

            for (int tableIndex = 0;
                 tableIndex < TABLE_COUNT_PER_DATABASE;
                 tableIndex++) {
                statement.execute(messageTableDdl(tableIndex));
                statement.execute(sendRecordTableDdl(tableIndex));
            }

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS sys_tenant
                    (
                        id          BIGINT       NOT NULL,
                        tenant_code VARCHAR(64)  NOT NULL,
                        tenant_name VARCHAR(128) NOT NULL,
                        status      TINYINT      NOT NULL DEFAULT 1,
                        deleted     TINYINT      NOT NULL DEFAULT 0,
                        created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                        updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                                  ON UPDATE CURRENT_TIMESTAMP(3),
                        version     INT          NOT NULL DEFAULT 0,
                        PRIMARY KEY (id),
                        UNIQUE KEY uk_tenant_code (tenant_code)
                    )
                    """);
        }
    }

    static void clear(MySQLContainer<?> container) throws SQLException {
        try (Connection connection = connection(container);
             Statement statement = connection.createStatement()) {
            // 先清从表，再清主表，方便未来补充真实外键实验。
            for (int index = 0; index < TABLE_COUNT_PER_DATABASE; index++) {
                statement.executeUpdate("DELETE FROM notify_send_record_" + index);
                statement.executeUpdate("DELETE FROM notify_message_" + index);
            }
            statement.executeUpdate("DELETE FROM sys_tenant");
        }
    }

    static int count(
            MySQLContainer<?> container,
            String physicalTable,
            long tenantId
    ) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + physicalTable
                + " WHERE tenant_id = " + tenantId;

        try (Connection connection = connection(container);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    static int countTenants(MySQLContainer<?> container) throws SQLException {
        try (Connection connection = connection(container);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT COUNT(*) FROM sys_tenant"
             )) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    /**
     * 返回某租户真正写入过的物理发送记录节点。
     * 这比只相信 logical SQL 日志更强：它直接检查两个 MySQL 的八张表。
     */
    static Set<PhysicalNode> activeSendRecordNodes(
            MySQLContainer<?> first,
            MySQLContainer<?> second,
            long tenantId
    ) throws SQLException {
        MySQLContainer<?>[] containers = {first, second};
        Set<PhysicalNode> result = new LinkedHashSet<>();

        for (int dbIndex = 0; dbIndex < containers.length; dbIndex++) {
            for (int tableIndex = 0;
                 tableIndex < TABLE_COUNT_PER_DATABASE;
                 tableIndex++) {
                String table = "notify_send_record_" + tableIndex;
                if (count(containers[dbIndex], table, tenantId) > 0) {
                    result.add(new PhysicalNode(dbIndex, tableIndex));
                }
            }
        }
        return result;
    }

    static Connection connection(MySQLContainer<?> container)
            throws SQLException {
        return DriverManager.getConnection(
                container.getJdbcUrl(),
                container.getUsername(),
                container.getPassword()
        );
    }

    private static String messageTableDdl(int tableIndex) {
        return """
                CREATE TABLE IF NOT EXISTS notify_message_%d
                (
                    id             BIGINT      NOT NULL,
                    tenant_id      BIGINT      NOT NULL,
                    message_no     VARCHAR(64) NOT NULL,
                    message_status VARCHAR(32) NOT NULL,
                    created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_message_no (tenant_id, message_no)
                )
                """.formatted(tableIndex);
    }

    private static String sendRecordTableDdl(int tableIndex) {
        return """
                CREATE TABLE IF NOT EXISTS notify_send_record_%d
                (
                    id                  BIGINT       NOT NULL,
                    tenant_id           BIGINT       NOT NULL,
                    message_id          BIGINT       NOT NULL,
                    event_id            VARCHAR(64)  NOT NULL,
                    attempt_no          INT          NOT NULL,
                    channel_type        VARCHAR(32)  NOT NULL,
                    idempotency_key     VARCHAR(128) NOT NULL,
                    send_status         VARCHAR(32)  NOT NULL,
                    provider_message_id VARCHAR(128),
                    failure_code        VARCHAR(64),
                    failure_reason      VARCHAR(1000),
                    started_at          DATETIME(3)  NOT NULL,
                    finished_at         DATETIME(3),
                    created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                    updated_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                                        ON UPDATE CURRENT_TIMESTAMP(3),
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_send_attempt
                        (tenant_id, message_id, attempt_no),
                    UNIQUE KEY uk_send_idempotency
                        (tenant_id, idempotency_key),
                    KEY idx_send_event (tenant_id, event_id),
                    KEY idx_send_status (tenant_id, send_status)
                )
                """.formatted(tableIndex);
    }

    record PhysicalNode(int databaseIndex, int tableIndex) {
    }
}
```

### 5.10 新增 `ShardingSphereDay23IntegrationTest.java`

路径：

```text
notification-infrastructure/src/test/java/com/tam/notification/sharding/ShardingSphereDay23IntegrationTest.java
```

完整代码：

```java
package com.tam.notification.sharding;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Day23 必做集成实验。
 *
 * 使用两个真实 MySQL 容器，每个容器四张发送记录表。
 * 测试同时检查逻辑结果和物理表分布，避免“配置能加载就算成功”。
 */
@Testcontainers
class ShardingSphereDay23IntegrationTest {

    private static final long TENANT_CRUD = 23_001L;
    private static final long TENANT_QUERY = 23_002L;
    private static final long TENANT_TRANSACTION = 23_003L;
    private static final long TENANT_BINDING = 23_004L;
    private static final long TENANT_BROKEN = 23_005L;

    private static final DockerImageName MYSQL_IMAGE =
            DockerImageName.parse("mysql:8.0");

    @Container
    private static final MySQLContainer<?> DS_0 =
            new MySQLContainer<>(MYSQL_IMAGE)
                    .withDatabaseName("notification_shard_0")
                    .withUsername("notification")
                    .withPassword("notification123");

    @Container
    private static final MySQLContainer<?> DS_1 =
            new MySQLContainer<>(MYSQL_IMAGE)
                    .withDatabaseName("notification_shard_1")
                    .withUsername("notification")
                    .withPassword("notification123");

    private static DataSource shardingDataSource;
    private static JdbcTemplate jdbc;
    private static JdbcTemplate brokenJdbc;

    @BeforeAll
    static void setUpShardingSphere() throws Exception {
        // Testcontainers 已经为两个容器分配动态端口。
        // 把真实连接信息提供给 YAML 的 $${...} 占位符。
        setDataSourceProperties("day23.ds0", DS_0);
        setDataSourceProperties("day23.ds1", DS_1);

        // actualDataNodes 不建表，必须先初始化两个真实 MySQL。
        Day23PhysicalSchema.create(DS_0);
        Day23PhysicalSchema.create(DS_1);

        Class.forName(
                "org.apache.shardingsphere.driver.ShardingSphereDriver"
        );

        shardingDataSource = shardingDataSource(
                "day23/sharding-day23.yaml"
        );
        jdbc = new JdbcTemplate(shardingDataSource);

        // 错误配置使用相同物理库，但拥有独立逻辑 DataSource。
        brokenJdbc = new JdbcTemplate(shardingDataSource(
                "day23/sharding-day23-broken.yaml"
        ));
    }

    @BeforeEach
    void cleanPhysicalTables() throws SQLException {
        Day23PhysicalSchema.clear(DS_0);
        Day23PhysicalSchema.clear(DS_1);
    }

    @AfterAll
    static void clearDynamicProperties() {
        for (String prefix : List.of("day23.ds0", "day23.ds1")) {
            System.clearProperty(prefix + ".jdbc-url");
            System.clearProperty(prefix + ".username");
            System.clearProperty(prefix + ".password");
        }
    }

    @Test
    void crudShouldUseOneShardAndGenerateGlobalId() throws SQLException {
        long firstMessageId = 12L;
        long secondMessageId = 13L;

        // INSERT 故意不提供 id，验证 ShardingSphere keyGenerateStrategy。
        insertSendRecord(jdbc, TENANT_CRUD, firstMessageId, 1, "PROCESSING");
        insertSendRecord(jdbc, TENANT_CRUD, secondMessageId, 1, "PROCESSING");

        Long firstId = findRecordId(TENANT_CRUD, firstMessageId);
        Long secondId = findRecordId(TENANT_CRUD, secondMessageId);

        assertNotNull(firstId);
        assertNotNull(secondId);
        assertTrue(firstId > 0);
        assertTrue(secondId > 0);
        assertNotEquals(firstId, secondId, "逻辑表范围内 ID 必须唯一");

        // messageId=12: (12 >> 2) & 1 = 1，12 & 3 = 0。
        // messageId=13: 同库、表后缀为 1。
        assertEquals(
                Set.of(
                        new Day23PhysicalSchema.PhysicalNode(1, 0),
                        new Day23PhysicalSchema.PhysicalNode(1, 1)
                ),
                Day23PhysicalSchema.activeSendRecordNodes(
                        DS_0,
                        DS_1,
                        TENANT_CRUD
                )
        );

        Integer readCount = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM notify_send_record
                WHERE tenant_id = ?
                  AND message_id = ?
                """, Integer.class, TENANT_CRUD, firstMessageId);
        assertEquals(1, readCount);

        // UPDATE 同时带 id、message_id、tenant_id：路由和隔离条件都完整。
        int updated = jdbc.update("""
                UPDATE notify_send_record
                SET send_status = 'SUCCESS',
                    provider_message_id = 'provider-day23',
                    finished_at = ?
                WHERE id = ?
                  AND message_id = ?
                  AND tenant_id = ?
                  AND send_status = 'PROCESSING'
                """, Timestamp.valueOf(LocalDateTime.now()),
                firstId, firstMessageId, TENANT_CRUD);
        assertEquals(1, updated);

        String status = jdbc.queryForObject("""
                SELECT send_status
                FROM notify_send_record
                WHERE tenant_id = ?
                  AND message_id = ?
                """, String.class, TENANT_CRUD, firstMessageId);
        assertEquals("SUCCESS", status);

        int deleted = jdbc.update("""
                DELETE FROM notify_send_record
                WHERE id = ?
                  AND message_id = ?
                  AND tenant_id = ?
                """, secondId, secondMessageId, TENANT_CRUD);
        assertEquals(1, deleted);
        assertNull(findRecordId(TENANT_CRUD, secondMessageId));
    }

    @Test
    void rangePaginationAndAggregationShouldMergeCorrectly()
            throws SQLException {
        for (long messageId = 100; messageId < 116; messageId++) {
            String status = messageId % 2 == 0 ? "SUCCESS" : "FAILED";
            insertSendRecord(
                    jdbc,
                    TENANT_QUERY,
                    messageId,
                    1,
                    status
            );
        }

        // 100..107 已经覆盖 2×4 的全部节点。
        assertEquals(
                8,
                Day23PhysicalSchema.activeSendRecordNodes(
                        DS_0,
                        DS_1,
                        TENANT_QUERY
                ).size()
        );

        Integer total = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM notify_send_record
                WHERE tenant_id = ?
                  AND message_id BETWEEN ? AND ?
                """, Integer.class, TENANT_QUERY, 100L, 115L);
        assertEquals(16, total);

        List<Long> page = jdbc.queryForList("""
                SELECT message_id
                FROM notify_send_record
                WHERE tenant_id = ?
                  AND message_id BETWEEN ? AND ?
                ORDER BY message_id
                LIMIT 5 OFFSET 5
                """, Long.class, TENANT_QUERY, 100L, 115L);
        assertEquals(List.of(105L, 106L, 107L, 108L, 109L), page);

        List<Map<String, Object>> aggregation = jdbc.queryForList("""
                SELECT send_status, COUNT(*) AS amount
                FROM notify_send_record
                WHERE tenant_id = ?
                GROUP BY send_status
                ORDER BY send_status
                """, TENANT_QUERY);

        assertEquals(2, aggregation.size());
        assertEquals(8L, amountOf(aggregation, "FAILED"));
        assertEquals(8L, amountOf(aggregation, "SUCCESS"));
    }

    @Test
    void tenantConditionMustStillBePartOfLogicalSql() {
        long sharedMessageId = 40L;
        long anotherTenant = TENANT_QUERY + 100;

        // 唯一索引包含 tenant_id，因此两个租户可以拥有相同 messageId/attemptNo。
        insertSendRecord(
                jdbc,
                TENANT_QUERY,
                sharedMessageId,
                1,
                "SUCCESS"
        );
        insertSendRecord(
                jdbc,
                anotherTenant,
                sharedMessageId,
                1,
                "SUCCESS"
        );

        Integer isolatedCount = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM notify_send_record
                WHERE message_id = ?
                  AND tenant_id = ?
                """, Integer.class, sharedMessageId, TENANT_QUERY);
        assertEquals(1, isolatedCount);

        Integer unsafeCount = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM notify_send_record
                WHERE message_id = ?
                """, Integer.class, sharedMessageId);
        assertEquals(
                2,
                unsafeCount,
                "ShardingSphere 负责路由，不会自动替代 MyBatis 租户拦截器"
        );
    }

    @Test
    void bindingJoinShouldStayOnOneNode() throws SQLException {
        long messageId = 53L;

        jdbc.update("""
                INSERT INTO notify_message
                    (id, tenant_id, message_no, message_status, created_at)
                VALUES (?, ?, ?, 'SENT', ?)
                """,
                messageId,
                TENANT_BINDING,
                "MSG-DAY23-" + messageId,
                Timestamp.valueOf(LocalDateTime.now())
        );
        insertSendRecord(
                jdbc,
                TENANT_BINDING,
                messageId,
                1,
                "SUCCESS"
        );

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT m.id AS message_id,
                       r.id AS send_record_id,
                       r.send_status
                FROM notify_message m
                JOIN notify_send_record r
                  ON m.id = r.message_id
                WHERE m.id = ?
                  AND m.tenant_id = ?
                  AND r.tenant_id = ?
                """, messageId, TENANT_BINDING, TENANT_BINDING);

        assertEquals(1, rows.size());
        assertEquals("SUCCESS", rows.get(0).get("send_status"));

        // 53: (53 >> 2) & 1 = 1，53 & 3 = 1。
        assertEquals(
                Set.of(new Day23PhysicalSchema.PhysicalNode(1, 1)),
                Day23PhysicalSchema.activeSendRecordNodes(
                        DS_0,
                        DS_1,
                        TENANT_BINDING
                )
        );
    }

    @Test
    void broadcastInsertShouldReachBothDatabases() throws SQLException {
        int inserted = jdbc.update("""
                INSERT INTO sys_tenant
                    (id, tenant_code, tenant_name, status, deleted, version)
                VALUES (?, ?, ?, 1, 0, 0)
                """, 23_900L, "day23", "Day23 Tenant");

        // JDBC 返回值可能反映多节点执行总数，不用它判断副本完整性。
        assertTrue(inserted >= 1);
        assertEquals(1, Day23PhysicalSchema.countTenants(DS_0));
        assertEquals(1, Day23PhysicalSchema.countTenants(DS_1));
    }

    @Test
    void businessRollbackShouldRollbackBothDatabases() throws Exception {
        try (Connection connection = shardingDataSource.getConnection()) {
            connection.setAutoCommit(false);

            try {
                // 200 路由 ds_0，204 路由 ds_1。
                insertSendRecord(
                        connection,
                        TENANT_TRANSACTION,
                        200L,
                        1,
                        "PROCESSING"
                );
                insertSendRecord(
                        connection,
                        TENANT_TRANSACTION,
                        204L,
                        1,
                        "PROCESSING"
                );

                // 模拟业务校验失败，不进入 commit。
                throw new IllegalStateException("day23 rollback probe");
            } catch (IllegalStateException expected) {
                connection.rollback();
            }
        }

        assertTrue(
                Day23PhysicalSchema.activeSendRecordNodes(
                        DS_0,
                        DS_1,
                        TENANT_TRANSACTION
                ).isEmpty()
        );
    }

    @Test
    void relatedModuloConfigurationShouldReachOnlyHalfNodes()
            throws SQLException {
        for (long messageId = 0; messageId < 8; messageId++) {
            insertSendRecord(
                    brokenJdbc,
                    TENANT_BROKEN,
                    messageId,
                    1,
                    "SUCCESS"
            );
        }

        Set<Day23PhysicalSchema.PhysicalNode> activeNodes =
                Day23PhysicalSchema.activeSendRecordNodes(
                        DS_0,
                        DS_1,
                        TENANT_BROKEN
                );

        System.out.println("broken active nodes = " + activeNodes);
        assertEquals(
                4,
                activeNodes.size(),
                "同一 messageId 分别 %2、%4，只能命中 4/8 个节点"
        );
    }

    private static void insertSendRecord(
            JdbcTemplate target,
            long tenantId,
            long messageId,
            int attemptNo,
            String status
    ) {
        target.update("""
                INSERT INTO notify_send_record
                    (tenant_id, message_id, event_id, attempt_no,
                     channel_type, idempotency_key, send_status, started_at)
                VALUES (?, ?, ?, ?, 'EMAIL', ?, ?, ?)
                """,
                tenantId,
                messageId,
                "event-" + tenantId + "-" + messageId + "-" + attemptNo,
                attemptNo,
                "idem-" + tenantId + "-" + messageId + "-" + attemptNo,
                status,
                Timestamp.valueOf(LocalDateTime.now())
        );
    }

    private static void insertSendRecord(
            Connection connection,
            long tenantId,
            long messageId,
            int attemptNo,
            String status
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO notify_send_record
                    (tenant_id, message_id, event_id, attempt_no,
                     channel_type, idempotency_key, send_status, started_at)
                VALUES (?, ?, ?, ?, 'EMAIL', ?, ?, ?)
                """)) {
            statement.setLong(1, tenantId);
            statement.setLong(2, messageId);
            statement.setString(3, "tx-event-" + messageId);
            statement.setInt(4, attemptNo);
            statement.setString(5, "tx-idem-" + tenantId + "-" + messageId);
            statement.setString(6, status);
            statement.setTimestamp(7, Timestamp.valueOf(LocalDateTime.now()));
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static Long findRecordId(long tenantId, long messageId) {
        List<Long> ids = jdbc.queryForList("""
                SELECT id
                FROM notify_send_record
                WHERE tenant_id = ?
                  AND message_id = ?
                """, Long.class, tenantId, messageId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private static long amountOf(
            List<Map<String, Object>> rows,
            String status
    ) {
        return rows.stream()
                .filter(row -> status.equals(row.get("send_status")))
                .map(row -> ((Number) row.get("amount")).longValue())
                .findFirst()
                .orElseThrow();
    }

    private static void setDataSourceProperties(
            String prefix,
            MySQLContainer<?> container
    ) {
        System.setProperty(prefix + ".jdbc-url", container.getJdbcUrl());
        System.setProperty(prefix + ".username", container.getUsername());
        System.setProperty(prefix + ".password", container.getPassword());
    }

    private static DataSource shardingDataSource(String yamlPath) {
        DriverManagerDataSource result = new DriverManagerDataSource();
        result.setDriverClassName(
                "org.apache.shardingsphere.driver.ShardingSphereDriver"
        );
        result.setUrl(
                "jdbc:shardingsphere:classpath:"
                        + yamlPath
                        + "?placeholder-type=system_props"
        );
        return result;
    }
}
```

### 5.11 为什么测试不直接复用 Flyway

当前 Flyway 脚本创建的是单库逻辑表名：

```text
notify_send_record
```

PoC 需要在每个真实数据源创建：

```text
notify_send_record_0 ... notify_send_record_3
```

直接让现有 Flyway 对 ShardingSphere 逻辑 DataSource 运行，会混淆“逻辑 DDL 路由”和“每个节点的物理 Schema 管理”。Day23 用测试辅助类显式建表，便于看清责任；生产 Schema 版本编排、滚动 DDL 和校验属于后续工程化内容。

## 六、实验验证

### 6.1 实验前检查

要求：

```text
JDK 17
Maven 3.9+
Docker Desktop 已启动
能够拉取 mysql:8.0
```

确认本次学习前工作区基线：

```bash
git rev-parse HEAD
git status --short
```

预期基线：

```text
3a4c4b2568a37562579510f7cee4416719dad7dd
```

### 6.2 先执行原有回归测试

```bash
mvn -pl notification-worker,notification-infrastructure \
    -am test
```

目的：证明接口签名增加 `messageId` 后，原有模块仍能编译，Worker 业务测试没有回归。

### 6.3 执行 Day23 集成测试

```bash
mvn -pl notification-infrastructure \
    -Dtest=ShardingSphereDay23IntegrationTest \
    -Dsurefire.failIfNoSpecifiedTests=false \
    test
```

首次执行会启动两个 MySQL 8 容器并下载 ShardingSphere 依赖，耗时会比普通单元测试长。

预期：

```text
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### 6.4 实验一：单键 CRUD 与物理路由

重点观察 `crudShouldUseOneShardAndGenerateGlobalId`。

对于 `message_id = 12`：

```text
dbIndex    = (12 >> 2) & 1 = 1
tableIndex = 12 & 3        = 0
```

SQL 日志应只出现类似：

```text
Actual SQL: ds_1 ::: INSERT INTO notify_send_record_0 ...
Actual SQL: ds_1 ::: SELECT ... FROM notify_send_record_0 ...
Actual SQL: ds_1 ::: UPDATE notify_send_record_0 ...
```

验收：

- INSERT 未传 `id`，仍得到正数且不重复的 ID；
- messageId 12、13 只写入 `ds_1.table_0/table_1`；
- SELECT、UPDATE、DELETE 结果正确；
- UPDATE/DELETE 同时包含 `message_id` 与 `tenant_id`。

### 6.5 实验二：范围查询、分页与聚合

`100..115` 会覆盖全部八个节点。日志中应看到八条 Actual SQL，而不是一条。

分页逻辑结果必须是：

```text
[105, 106, 107, 108, 109]
```

聚合结果：

```text
FAILED  = 8
SUCCESS = 8
```

然后把测试中的：

```sql
LIMIT 5 OFFSET 5
```

临时改成：

```sql
LIMIT 5 OFFSET 10000
```

只观察日志，不提交这个临时改动。回答：每个物理节点需要返回多少候选数据，网络传输和内存归并为什么放大？

### 6.6 实验三：租户隔离边界

测试为两个租户写入相同 `message_id + attempt_no`：

```text
带 tenant_id 查询 → 1 行
不带 tenant_id 查询 → 2 行
```

这证明：

```text
ShardingSphere 只根据 message_id 路由
MyBatis TenantLineInnerInterceptor 才负责 tenant_id 隔离
```

学习验收时还要检查真实 Mapper 日志，确认 `markSuccess/markFailed` 最终 SQL 包含 `tenant_id`。

### 6.7 实验四：绑定表

`message_id = 53`：

```text
dbIndex = 1
tableIndex = 1
```

预期 JOIN 只在：

```text
ds_1.notify_message_1
JOIN
ds_1.notify_send_record_1
```

执行。若删除 `bindingTables` 再运行，比较 Actual SQL 数量。完成观察后恢复配置。

### 6.8 实验五：广播表

逻辑插入一次 `sys_tenant` 后，辅助类直接连接两个 MySQL，断言两边各有一行。

必须能解释：

- 广播表适合小型字典/配置，不适合高频大表；
- 广播写会放大为 N 份；
- ShardingSphere 执行成功不代表所有副本的长期一致性不需要监控；
- `sys_tenant` 在本课只是实验对象，不是已经完成的生产选型。

### 6.9 实验六：事务

测试分别向 `ds_0` 和 `ds_1` 写一行，然后抛出业务异常并调用 rollback。两个库都应为 0 行。

验收答案必须包含：

```text
已验证：业务异常触发的跨库回滚
未验证：提交阶段数据库宕机时的原子性
未引入：XA/BASE
现有业务主要依靠：Outbox + MQ 幂等 + 状态机实现最终一致性
```

### 6.10 实验七：真实复现错误路由

错误配置插入 `message_id = 0..7`。预期输出：

```text
broken active nodes = [四个节点]
```

断言：

```text
activeNodes = 4，而不是 8
```

把四个节点手算出来：

```text
ds_0.table_0
ds_1.table_1
ds_0.table_2
ds_1.table_3
```

另外四个组合从数学上永远不可达。

### 6.11 实验八：证明缺少分片键会广播

先观察带 `message_id` 的 UPDATE，确认只有一条 Actual SQL。然后仅在本地实验中临时删除：

```sql
AND message_id = #{messageId}
```

运行一条按 `id` 更新的逻辑 SQL，日志应出现八条物理 UPDATE。观察完成后立即恢复。

不要把错误版本提交。这个实验的目的，是建立下面的代码审查条件反射：

```text
看到分片表 UPDATE/DELETE
→ 先检查 WHERE 是否携带分片键
→ 再检查是否携带 tenant_id 和业务状态条件
```

### 6.12 最终验收清单

- [ ] 能画出解析、路由、改写、执行、归并五个阶段；
- [ ] 两个真实 MySQL、八张发送记录物理表可运行；
- [ ] 单键 CRUD 只命中一个节点；
- [ ] `markSuccess/markFailed` 已携带 `messageId`；
- [ ] 多租户条件没有被 ShardingSphere 替代；
- [ ] 范围、分页和聚合结果正确，并看到八节点 SQL；
- [ ] 绑定表 JOIN 只命中一个对应节点组合；
- [ ] 广播表写入两个库；
- [ ] Snowflake 生成的逻辑 ID 不重复；
- [ ] 业务异常跨库回滚通过，且没有夸大成本地事务强一致；
- [ ] 错误配置稳定复现只命中 4/8 节点；
- [ ] 能说明 Day23 为什么不切换生产默认数据源；
- [ ] 全量相关测试通过，`git diff --check` 无格式问题。

## 七、面试追问

### 7.1 ShardingSphere 帮你透明了什么，又没有透明什么？

它透明了逻辑表到物理节点的路由、SQL 改写、多节点执行与部分结果归并，使 MyBatis 仍面向标准 JDBC。它没有替业务决定分片键、容量阈值、租户隔离、热点治理、跨分片查询是否合理、分布式事务选型、在线扩容、数据回填和对账。

### 7.2 为什么选 `notify_send_record.message_id` 做分片键？

发送记录按消息产生多个 attempt，当前高频查询是 `messageId + attemptNo`，将同一消息的发送尝试放在同一分片可以单节点查询，并与 Worker 的重投/DLQ 语义一致。代价是只按状态、时间或租户查询会跨分片。

### 7.3 为什么成功更新必须同时带 `id` 和 `message_id`？

`id` 精确标识业务行，`message_id` 负责分片路由。只有 id 时 ShardingSphere 无法知道物理节点，只能广播；只有 messageId 时同一消息可能有多次 attempt，更新范围过大。再叠加 tenantId 和 PROCESSING 状态，才同时满足路由、隔离与状态机约束。

### 7.4 ShardingSphere 能自动保证多租户隔离吗？

不能。当前隔离由 TenantContext 和 MyBatis TenantLineInnerInterceptor 完成。ShardingSphere 只处理它收到的 SQL；如果上游 SQL 没有 tenant_id，它不会凭空知道当前租户。

### 7.5 `INLINE` 标准策略为什么不适合所有场景？

它适合单分片键的简单等值/IN 路由，表达式清晰、成本低。复杂复合键、区间规则、动态拓扑、热点拆分或定制容错需要 Complex、Hint 或自定义算法。范围查询在本实验中允许执行，但会全路由。

### 7.6 分库与分表为什么不能都对同一个 hash 直接取模？

当两个模数存在公因数，库索引和表索引相关，可达节点数小于笛卡尔积。2 库 × 4 表直接 `%2/%4` 只有 4 个组合可达。独立位段或独立哈希可以解除相关性。

### 7.7 广播表和绑定表的区别？

广播表在每个数据源保存完整副本，适合小型公共数据；绑定表是分片主从表的关系声明，要求路由规则一致，用来减少关联查询的路由组合。广播表关注复制，绑定表关注共址关联。

### 7.8 声明绑定表后，数据一定在同一个节点吗？

不一定。配置声明不会修正错误算法。两张表必须使用同一业务分片值、相同拓扑和等价路由规则，JOIN 条件也要体现绑定关系。本课用 `notify_message.id = notify_send_record.message_id` 验证物理共址。

### 7.9 分片后为什么不能继续依赖数据库自增 ID？

各物理表独立自增会生成重复值，逻辑表范围内不再唯一。可以使用应用侧 Snowflake、ShardingSphere key generator、号段服务或 UUID。当前项目生产路径保留 MyBatis-Plus ASSIGN_ID，PoC 额外验证 ShardingSphere Snowflake。

### 7.10 配置了 ShardingSphere Snowflake，为什么生产 INSERT 可能没有使用它？

因为 `SendRecordDO` 的 ASSIGN_ID 已在 MyBatis 层填充 id。Key generator 只在 SQL 没有提供目标列时生效。主键生成责任必须明确归属，不能看到 YAML 就假设实际调用链一定使用它。

### 7.11 Snowflake ID 天然适合直接取模分片吗？

不一定。连续时间段生成的 ID 低位可能分布不足，官方提供 `max-vibration-offset` 改善按 `2^n` 取模的低位分布。但生产选型仍要通过真实生成模型做分布测试，并保证 worker-id 唯一和处理时钟回拨。

### 7.12 跨分片分页为什么慢？

各分片需要局部筛选和排序，ShardingSphere 再全局归并。大 OFFSET 会让每个分片读取并传输大量最终被丢弃的行。常见改进是携带分片键、使用游标分页、限制深翻页或建立异步汇总查询模型。

### 7.13 跨分片 COUNT/GROUP BY 是否一定错误？

不一定，ShardingSphere可以归并很多常见聚合并返回正确结果，但代价取决于节点数、扫描行数、分组基数和网络传输。在线高频全局统计通常应预聚合，而不是每次扫描所有 OLTP 分片。

### 7.14 本地事务能保证跨库强一致吗？

业务异常触发 rollback 时可以回滚已参与的连接，但提交阶段发生网络或硬件故障时，本地事务不能保证所有库原子提交。需要根据业务选择 XA、BASE、Saga/补偿或 Outbox 最终一致性，并接受相应性能和可用性代价。

### 7.15 为什么 Day23 不直接引入 XA？

本日目标是理解分片路由和查询行为。通知发送链路已有 Outbox、MQ 幂等与状态机，强行加入 XA 会把事务管理器、恢复、权限与性能问题混入路由实验。先证明本地事务边界，再由具体业务不变量决定是否需要 XA。

### 7.16 ShardingSphere 接入后，Flyway 为什么变复杂？

逻辑表下面有多个物理表和数据源，DDL 需要保证所有节点版本一致、支持失败重试和分批发布，并避免路由中间件误改写。生产中通常要有独立的物理 Schema 编排与校验，而不能只对逻辑 DataSource 跑一次原单库脚本。

### 7.17 如何防止无分片键 UPDATE/DELETE 拖垮集群？

首先让 Repository API 显式要求分片键；其次在 SQL 审查和测试中验证 Actual SQL 节点数；再配置分片审计、慢 SQL/广播路由指标和权限保护。高风险后台操作应离线化、分批并带明确路由条件。

### 7.18 2 库 × 4 表验证成功后，为什么不能直接扩到 32 × 256？

PoC 只证明配置和路由语义。生产节点数必须由真实保留行数、行宽、写入 QPS、查询 SLA、连接数、DDL、备份恢复和扩容模型决定。8192 个节点会显著放大元数据、连接、运维和广播查询成本。

### 7.19 ShardingSphere 能自动完成在线扩容吗？

不能把它理解为改一行 `actualDataNodes` 就完成扩容。路由改变后，旧数据仍在旧节点，需要新旧路由版本、可靠双写、游标回填、checkpoint、对账、灰度切读和回滚窗口。Day24、Day25 才完成这个闭环。

### 7.20 如果面试官问本项目 Day23 的真实成果，怎么回答？

可以回答：

> 我没有把训练项目直接拆成 32 库 256 表，而是先通过容量模型选择发送记录表，再搭建两个真实 MySQL、每库四表的 ShardingSphere-JDBC PoC。使用 messageId 的独立位段保证八个节点可达，自动化覆盖 CRUD、租户条件、范围分页、聚合、绑定表、广播表、Snowflake 和跨库业务回滚。我还在真实配置中复现了 `%2/%4` 只能命中 4/8 节点，并发现现有完成发送 SQL 只按 id 更新会广播，因此把 messageId 沿 Repository 调用链传入。生产默认数据源没有提前切换，因为在线双写、回填和对账属于下一阶段。
