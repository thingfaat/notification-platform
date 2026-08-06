## 二、总体架构

首月采用：

> **Maven 多模块 + 模块化单体 + 独立消息消费者**

不要一开始拆成十几个微服务。模块边界先设计清楚，后续需要时再独立部署。

```markdown
┌─────────────────────┐
│ 业务系统 / 管理端 │
└──────────┬──────────┘
            │ HTTP
            ▼
┌─────────────────────────────────────────────────────────┐
│ notification-server │
│ 鉴权、租户识别、参数校验、发送任务创建、任务查询 │
└────────────────────────┬────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────┐
│ notification-core │
│ 模板渲染、任务编排、状态机、渠道路由、限流规则、领域模型 │
└───────────────┬────────────────────────┬────────────────┘
                │                       │
                ▼                       ▼
┌───────────────────────────┐ ┌──────────────────────────┐
│ notification-infrastructure│ │ message_outbox │
│ MySQL / Redis / RocketMQ │ │ 本地消息表 │
└───────────────────────────┘ └────────────┬─────────────┘
                                            │ RocketMQ
                                            ▼
                        ┌──────────────────────────────┐
                        │ notification-worker │
                        │ 消费、幂等、路由、重试、回写 │
                        └──────────────┬───────────────┘
                                        │
                                        ▼
                        ┌──────────────────────────────┐
                        │ notification-channel │
                        │ 短信 / 邮件 / 站内信适配器 │
                        └──────────────────────────────┘

业务消息中包含短链时：

notification-core
        │
        ▼
notification-shortlink
        │
        ├── MySQL
        └── Redis
```

## 核心发送链路

```markdown
接收发送请求
→ 识别租户和应用
→ 校验模板及渠道
→ 渲染消息内容
→ 创建消息任务和发送明细
→ 在同一事务中写入 Outbox
→ Outbox 消息投递 RocketMQ
→ Worker 消费消息
→ 幂等校验
→ 限流及渠道路由
→ 调用渠道适配器
→ 更新发送状态
→ 失败重试或进入死信
```

这里使用 Outbox 的目的，是避免：

```markdown
数据库任务创建成功
但 MQ 消息发送失败
```

导致任务永远得不到处理。

---

## 三、模块边界

建议使用以下 Maven 多模块结构：

```markdown
notification-platform
├── pom.xml
├── README.md
├── docs
│ ├── architecture.md
│ ├── database-design.md
│ ├── message-state-machine.md
│ └── tenant-isolation.md
│
├── notification-common
├── notification-core
├── notification-infrastructure
├── notification-channel
├── notification-server
├── notification-worker
└── notification-shortlink
```

## 1\. notification-common

只存放真正的通用代码：

- 统一返回对象；
- 错误码；
- 基础异常；
- 公共枚举；
- ID生成接口；
- TenantContext；
- TraceId工具；
- 通用常量。

不能把所有工具类都扔进 common。

---

## 2\. notification-core

平台核心业务，不依赖具体中间件实现：

- 租户、应用领域模型；
- 模板领域模型；
- 消息任务和消息明细；
- 模板渲染；
- 消息状态机；
- 渠道路由规则；
- 发送任务编排；
- 重试策略；
- 幂等接口；
- Repository 接口；
- MQ发布接口；
- Redis限流接口。

这里定义能力，但不直接编写 MySQL、Redis、RocketMQ 代码。

---

## 3\. notification-infrastructure

实现 core 定义的基础设施接口：

- MyBatis Mapper；
- Repository 实现；
- Redis操作；
- RocketMQ生产者；
- Outbox发布器；
- 分布式锁；
- 数据库字段自动填充；
- 多租户SQL拦截器。

---

## 4\. notification-channel

定义并实现渠道适配器：

```markdown
public interface ChannelSender {

    ChannelType supportType();

    SendResult send(SendCommand command);

}
```

首周实现：

- `MockSmsChannelSender`
- `MockEmailChannelSender`
- `InAppChannelSender`

后面再增加真实邮件或短信厂商适配器。

---

## 5\. notification-server

对外 HTTP 入口：

- 租户和应用管理；
- 模板管理；
- 创建发送任务；
- 批量发送；
- 延时发送；
- 查询任务；
- 查询发送明细；
- 取消未执行任务；
- Spring Security；
- TenantContext 初始化。

---

## 6\. notification-worker

RocketMQ 消费端：

- 消费消息；
- 恢复租户上下文；
- 消费幂等；
- 执行限流；
- 渠道路由；
- 渠道调用；
- 重试；
- 状态回写；
- 死信处理。

`server` 和 `worker` 可以分别启动，但共用 `core` 和 `infrastructure` 。

---

## 7\. notification-shortlink

短链子系统：

- 生成短码；
- 短链跳转；
- Redis缓存；
- 防缓存穿透；
- 点击事件异步统计；
- 短链有效期；
- 租户隔离。

---

## 四、技术栈定版

Day 1 不再纠结选型，直接采用：

| 分类      | 技术                                |
|---------|-----------------------------------|
| Java    | JDK 17                            |
| 基础框架    | Spring Boot 3.x                   |
| 构建工具    | Maven 多模块                         |
| 数据库     | MySQL 8                           |
| ORM     | MyBatis-Plus                      |
| 数据库版本管理 | Flyway                            |
| 缓存      | Redis                             |
| 消息队列    | RocketMQ                          |
| 安全认证    | Spring Security + JWT             |
| 参数校验    | Jakarta Validation                |
| 对象转换    | MapStruct                         |
| 接口文档    | SpringDoc OpenAPI                 |
| 单元测试    | JUnit 5 + Mockito                 |
| 集成测试    | Testcontainers                    |
| 本地环境    | Docker Compose                    |
| 监控      | Micrometer + Prometheus + Grafana |
| 链路标识    | TraceId，后续接 OpenTelemetry         |

今天只需要初始化基础 Maven 依赖，不需要一次性把全部中间件代码写出来。

---