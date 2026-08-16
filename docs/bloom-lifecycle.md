# Short Link Bloom Lifecycle

## 决策

- 只使用 Redis Cluster 共享时间分片，不增加本地 Bloom 和 Redis Stream。
- UTC 6 小时一片，默认保留 4 片。
- 每次轮换把 MySQL 中 ACTIVE 且未过期的短码完整重建到当前片。
- 新短链在事务提交后增量写当前片。
- ready 必须最后写；ready 不可信、重建中或 Redis 故障时一律 fail-open。

## 参数口径

- expectedInsertions：100000 个仍有效短码/完整快照片。
- overallFalsePositiveProbability：查询全部保留片后的 1%。
- retainedSliceCount：4。
- 计算结果：每片 1246262 bits、9 个哈希位置、约 152.13 KiB。

## 生命周期

1. 删除 ready 和当前片。
2. 查询 MySQL 有效短码。
3. 每 500 个短码批量写 Bitmap。
4. 设置 Bitmap TTL 并登记 ZSET。
5. 写 ready=currentSlice。
6. 删除窗口外片；TTL 作为兜底。

## 正确性边界

- Bloom false 只有在 ready 与当前片都可信时才能拦截。
- MySQL 是事实来源；Bloom 故障只影响性能。
- 过期短码在历史片保留期间只可能带来额外回源，不会误伤合法短链。
- 若增量写失败，删除 ready 并等待定时完整重建。

## 实验结果

记录日期、提交、样本数、每片写入数、理论总体误判率、实际误判数、实际误判率、Redis 内存和测试耗时。没有原始输出的数字不进入简历。