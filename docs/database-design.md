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