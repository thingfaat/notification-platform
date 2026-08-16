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