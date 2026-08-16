# Short Link Bloom Lifecycle

## 决策

- 只使用 Redis Cluster 共享时间分片，不增加本地 Bloom 和 Redis Stream。
- UTC 6 小时一片，默认保留 4 片。
- 每次轮换把 MySQL 中 ACTIVE 且未过期的短码完整重建到当前片。
- 新短链在事务提交后增量写当前片。
- ready 必须最后写；ready 不可信、重建中或 Redis 故障时一律 fail-open。
- 完整重建使用同槽分布式锁；抢锁、撤销 ready、分批写入、发布和中止均校验随机 token。

## 参数口径

- expectedInsertions：100000 个仍有效短码/完整快照片。
- overallFalsePositiveProbability：查询全部保留片后的 1%。
- retainedSliceCount：4。
- 计算结果：每片 1246262 bits、9 个哈希位置、约 152.13 KiB。

## 生命周期

1. Lua 原子抢占带 TTL 的 token 锁，并删除 ready 和当前片。
2. 查询 MySQL 有效短码。
3. 每 500 个短码校验 token 后批量写 Bitmap。
4. Lua 再次校验 token，设置 Bitmap TTL 并登记 ZSET。
5. 在同一个 Lua 中写 ready=currentSlice，并释放重建锁。
6. 删除窗口外片；TTL 作为兜底。
7. MySQL 扫描、写入或发布失败时，比较 token 后安全中止；锁 TTL 作为最终兜底。

## 正确性边界

- Bloom false 只有在 ready 与当前片都可信时才能拦截。
- MySQL 是事实来源；Bloom 故障只影响性能。
- 过期短码在历史片保留期间只可能带来额外回源，不会误伤合法短链。
- 若增量写失败，删除 ready 并等待定时完整重建。
- 多实例只有锁持有者可以删除当前片和发布 ready；锁过期的旧实例不能继续写批次或删除新锁。

## 实验结果

- 记录日期：2026-08-16
- 修复前基线提交：`90f2bbc08404fb18d6d999c7a6d86b864ca561cf`
- 修复版本：当前工作区，提交后补充最终 commit
- Redis：Redis 7，6 节点 Cluster
- 实验参数：每片写入 2000 个短码，保留 4 片
- 理论总体误判率：0.01
- 不存在样本数：20000
- 实际误判数：192
- 实际误判率：0.009600
- 当前片到最旧片 `MEMORY USAGE`：7256、3672、6232、7256 bytes
- 时间分片测试耗时：12.24 秒
- Maven 集群实验总耗时：14.075 秒
- 双实例交错重建：第一个实例写完 500 条后暂停，第二个实例抢锁失败；最终第 0 条和第 999 条均命中
- 锁安全实验：主动中止后另一实例可立即接管；锁 token 丢失后旧实例无法写批次或发布 ready

原始输出：

```text
samples=20000, falsePositives=192, actualRate=0.009600,
bitmapMemoryBytes=[7256, 3672, 6232, 7256]
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0,
Time elapsed: 12.24 s
BUILD SUCCESS
Total time: 14.075 s
```
