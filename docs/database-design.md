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

## Flyway今天真正开始发挥作用

建立：

```
notification-infrastructure
└── src/main/resources
└── db
└── migration
└── V1__init_base_tables.sql
```

把刚才四张表全部写进去。 然后：

```
mvn -pl notification-server -am spring-boot:run
```

启动时：

```
Spring Boot
↓
Flyway
↓
检查 flyway_schema_history
↓
发现 V1 未执行
↓
执行 V1__init_base_tables.sql
↓
记录执行历史
```

以后：

```
V2__init_notification_task.sql
V3__add_xxx_index.sql
```

不要修改已经发布执行过的 V1。

这点面试经常问： 为什么用了 Flyway 后不能直接修改历史 SQL？ 因为数据库结构变化本身也是版本历史。

正确：

```
V1 建表
V2 增字段
V3 建索引
```

错误：

```
V1执行过了
↓
直接回去修改V1
```

否则：

```
开发环境
测试环境
生产环境
```

数据库版本就可能不一致。