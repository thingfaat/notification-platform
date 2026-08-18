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