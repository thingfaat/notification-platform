# 面向企业内部业务系统的多租户统一消息通知平台

## Day 1 最终交付目标

完成以下三项即可验收：

1. 项目架构和模块边界确定；
2. README、架构说明、数据库草图、消息状态机完成；
3. Maven 多模块项目能够编译，并产生第一个规范 Commit。

---

## 一、项目定位

项目名称暂定：

```markdown
多租户统一通知平台
Multi-Tenant Notification Platform
```

仓库名称：

```markdown
notification-platform
```

项目定位写入 README：

> 面向企业内部业务系统的多租户统一消息通知平台，为不同租户和应用提供短信、邮件、站内信等统一发送能力，并通过消息队列实现异步处理、削峰填谷、失败重试、消费幂等、渠道路由和发送状态追踪。

## 首月必须完成

- 租户与应用管理；
- 渠道账号管理；
- 消息模板管理；
- 单条和批量消息发送；
- RocketMQ 异步发送；
- 消费幂等；
- 失败重试与死信处理；
- 消息状态机；
- 渠道路由与降级；
- 延时通知；
- 发送记录查询；
- 短链生成和跳转；
- Redis 限流；
- Docker Compose；
- 基础监控和压测报告。

## 首月明确不做

- 完整运营管理前端；
- 计费、套餐、账单系统；
- 复杂工作流编排；
- 同时接入十几个真实厂商；
- 一开始拆分大量微服务；
- 为了简历强行宣称百万 QPS；
- Kubernetes 高可用集群的深度建设。

真实短信、邮件渠道首周先通过模拟适配器跑通，后续再接入一个真实邮件渠道即可。

---

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
│ │
▼ ▼
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

## 五、数据库边界

Day 1 先确定核心表，不要求把所有字段一次设计完。

## 核心表清单

```markdown
sys_tenant 租户
sys_application 租户应用
notify_channel_account 渠道账号
notify_template 消息模板
notify_task 发送任务
notify_message 单个接收人发送明细
notify_outbox 本地消息表
notify_send_record 每次渠道调用记录
short_link 短链
short_link_click 短链点击明细，后期可异步统计
```

## 表之间的关系

```markdown
tenant
└── application
├── channel_account
├── template
└── task
└── message
└── send_record
```

## notify_task

表示一次业务发送请求，例如：

```markdown
向500个客户发送还款提醒
```

关键字段：

```markdown
id
tenant_id
application_id
request_id
template_id
channel_type
task_status
schedule_time
total_count
success_count
failed_count
created_at
updated_at
version
```

`request_id` 用于请求级幂等。

唯一索引：

```markdown
unique(tenant_id, application_id, request_id)
```

---

## notify_message

一条接收人对应一条发送明细：

```markdown
id
tenant_id
task_id
message_no
receiver
template_params
rendered_content
message_status
retry_count
next_retry_time
provider_message_id
failure_code
failure_reason
created_at
updated_at
version
```

唯一索引：

```markdown
unique(tenant_id, message_no)
```

---

## notify_outbox

关键字段：

```markdown
id
tenant_id
aggregate_type
aggregate_id
event_type
payload
publish_status
retry_count
next_retry_time
created_at
published_at
```

发送任务、发送明细和 Outbox 记录在同一个本地事务中保存。

---

## 六、消息状态机

需要区分：

- 任务状态；
- 单条消息状态；
- 渠道调用记录状态。

不要使用一个状态字段表示所有层次。

## 单条消息状态

```markdown
CREATED
│
▼
QUEUED
│
▼
SENDING
├──────────────► SENT
│ │
│ ├──► DELIVERED
│ └──► DELIVERY_FAILED
│
└──► RETRY_WAIT
│
├──► QUEUED
└──► DEAD

CREATED / QUEUED ──► CANCELLED
```

状态含义：

| 状态              | 含义           |
|-----------------|--------------|
| CREATED         | 明细已创建，尚未进入MQ |
| QUEUED          | 已成功进入消息队列    |
| SENDING         | Worker正在发送   |
| SENT            | 渠道已接收请求      |
| DELIVERED       | 渠道确认送达       |
| DELIVERY_FAILED | 渠道回执发送失败     |
| RETRY_WAIT      | 等待下一次重试      |
| DEAD            | 超过最大重试次数     |
| CANCELLED       | 尚未发送前被取消     |

对于不支持回执的渠道， `SENT` 可以作为当前可确认的最终技术状态。

## 任务聚合状态

```markdown
CREATED
PROCESSING
SUCCESS
PARTIAL_SUCCESS
FAILED
CANCELLED
```

任务状态不要由业务代码随意修改，应根据消息明细聚合计算：

```markdown
全部成功 → SUCCESS
部分成功、部分失败 → PARTIAL_SUCCESS
全部失败 → FAILED
仍存在处理中明细 → PROCESSING
```

---

## 七、多租户隔离方案

本项目采用：

> **共享数据库、共享 Schema、所有业务表增加 tenant_id。**

这是首月最适合的方案，能够展示多租户能力，又不会把时间浪费在动态数据源和独立数据库运维上。

## 强制规则

### 1\. 所有租户业务表必须包含 tenant_id

例如：

```markdown
notify_template
notify_task
notify_message
channel_account
short_link
```

### 2\. 唯一索引必须包含 tenant_id

错误：

```markdown
unique(template_code)
```

正确：

```markdown
unique(tenant_id, application_id, template_code)
```

### 3\. tenant_id 不接受普通请求参数直接传入

生产环境：

```markdown
JWT
→ 解析登录主体
→ 获取 tenantId
→ 写入 TenantContext
```

本地开发环境可以临时通过：

```markdown
X-Tenant-Id: 10001
```

模拟，但只能在 `dev` Profile 下启用。

### 4\. MyBatis 自动追加租户条件

查询：

```markdown
select \*
from notify_task
where id = ?
and tenant_id = ?
```

新增时自动填充 `tenant_id` 。

### 5\. MQ消息必须携带 tenantId

消息信封建议：

```markdown
{
"eventId": "事件唯一编号",
"tenantId": 10001,
"applicationId": 20001,
"messageId": 30001,
"eventType": "NOTIFICATION_SEND",
"traceId": "链路编号",
"occurredAt": "时间"
}
```

Worker 消费时：

```markdown
读取 tenantId
→ 建立 TenantContext
→ 执行业务
→ finally 清理 TenantContext
```

否则线程池复用会产生租户上下文污染。

### 6\. Redis Key必须包含租户维度

```markdown
notify:tenant:{tenantId}:template:{templateId}
notify:tenant:{tenantId}:rate:{channel}
shortlink:tenant:{tenantId}:{shortCode}
```

### 7\. 超级管理员绕过租户过滤必须显式控制

不能随意使用 ThreadLocal 开关跳过隔离。需要：

- 指定管理员角色；
- 显式注解；
- 操作日志；
- 用完立即清理上下文。

---

## 八、README结构

今天的 README 至少包含：

```markdown
# Multi-Tenant Notification Platform

## 1. 项目介绍

## 2. 建设目标

## 3. 首月范围

## 4. 非目标范围

## 5. 总体架构

## 6. 模块说明

## 7. 核心发送流程

## 8. 消息状态机

## 9. 多租户隔离方案

## 10. 技术栈

## 11. 数据库设计

## 12. 本地启动方式

## 13. 项目路线图

## 14. 项目文档
```

其中详细内容可以拆到：

```markdown
docs/architecture.md
docs/database-design.md
docs/message-state-machine.md
docs/tenant-isolation.md
```

README 只保留概要和链接。

---

## 九、第一笔 Commit

建议 Commit 信息：

```markdown
git commit -m "feat: initialize notification platform architecture"
```

此次 Commit 应包含：

```markdown
pom.xml
README.md
.gitignore
docs/architecture.md
docs/database-design.md
docs/message-state-machine.md
docs/tenant-isolation.md
各Maven模块的pom.xml
各启动模块的基础Application类
```

不要在这个 Commit 混入：

Day 1 的 Commit 就是架构基线。

---

## 十、今晚执行顺序

考虑到现在已经较晚，控制在约 3 小时。

| 时间   | 任务                        |
|------|---------------------------|
| 30分钟 | 整理README中的项目范围与非目标        |
| 45分钟 | 建立Maven多模块目录及依赖关系         |
| 60分钟 | 编写四份docs设计文档              |
| 30分钟 | 检查模块边界和依赖方向               |
| 15分钟 | `mvn clean verify` 、提交Git |

## Day 1 验收标准

完成后逐项核对：

- 项目范围明确，没有把点餐、RAG等内容混进通知平台；
- Maven模块均已建立；
- `notification-core` 不直接依赖 MySQL、Redis、RocketMQ 实现；
- README能够让陌生开发者理解项目目标；
- 数据库核心表和关系已经确定；
- 任务状态和消息状态分开设计；
- 多租户隔离覆盖数据库、缓存、MQ和线程上下文；
- `mvn clean verify` 执行成功；
- 已完成首个 Commit。

先把你当前项目的 **根目录结构和根 `pom.xml`** 发出来。我会按这套定版方案检查哪些代码保留、哪些模块需要调整，避免你重新搭一遍。
