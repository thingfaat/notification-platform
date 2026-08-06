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