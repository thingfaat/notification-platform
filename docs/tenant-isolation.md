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

```sql
select *
from notify_task
where id = ?
and tenant_id = ?
```

新增时自动填充 `tenant_id` 。

### 5\. MQ消息必须携带 tenantId

消息信封建议：

```json
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