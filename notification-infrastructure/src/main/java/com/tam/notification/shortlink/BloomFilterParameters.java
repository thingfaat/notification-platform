package com.tam.notification.shortlink;

/**
 * Bloom参数计算结果
 *
 * @param expectedInsertions               每个完整快照照片预计容纳的有效短码数
 * @param overallFalsePositiveProbability  查询全部保留片的总体误判率目标
 * @param perSliceFalsePositiveProbability 单片误判率目标
 * @param bitSize                          单片位数 m
 * @param hashFunctions                    哈希函数数量 k
 */
public record BloomFilterParameters(
        long expectedInsertions,
        double overallFalsePositiveProbability,
        double perSliceFalsePositiveProbability,
        long bitSize,
        int hashFunctions
) {
    // Redis String 最大 512 MiB，即最多 2^32 个 bit。
    private static final long MAX_REDIS_BITMAP_BITS = 1L << 32;

    /**
     * 根据预期插入量、总体误判率目标和保留片数，计算布隆过滤器的核心参数。
     * <p>
     * 计算流程：
     * <ol>
     *   <li>由总体误判率反推单片误判率</li>
     *   <li>由单片误判率计算最优位数组大小 m</li>
     *   <li>由位数组大小和预期插入量计算最优哈希函数数量 k</li>
     *   <li>校验位数组大小不超过 Redis 512 MiB 限制</li>
     * </ol>
     *
     * @param expectedInsertions              每个完整快照预计容纳的有效短码数，必须为正整数
     * @param overallFalsePositiveProbability 查询全部保留片时的总体误判率目标，取值范围 (0, 1)
     * @param retainedSliceCount              保留的快照片数量，必须为正整数
     * @return 包含单片误判率、位数组大小、哈希函数数量等计算结果的 BloomFilterParameters 实例
     * @throws IllegalArgumentException 当参数不合法或计算结果超出 Redis 位图上限时抛出
     */
    public static BloomFilterParameters calculate(
            long expectedInsertions,
            double overallFalsePositiveProbability,
            int retainedSliceCount
    ) {
        // 校验三个输入参数的合法性
        if (expectedInsertions <= 0) {
            throw new IllegalArgumentException("expectedInsertions must be positive");
        }
        if (!(overallFalsePositiveProbability > 0.0
                && overallFalsePositiveProbability < 1.0)) {
            throw new IllegalArgumentException(
                    "overallFalsePositiveProbability must be between 0 and 1"
            );
        }
        if (retainedSliceCount <= 0) {
            throw new IllegalArgumentException("retainedSliceCount must be positive");
        }

        // 查询 r 个片时：p_total = 1 - (1 - p_slice)^r。
        // 由总体误判率反推每个片允许的误判率
        double perSliceProbability = 1.0 - Math.pow(
                1.0 - overallFalsePositiveProbability,
                1.0 / retainedSliceCount
        );

        // 根据布隆过滤器最优公式 m = -n * ln(p) / (ln2)^2 计算单片所需位数
        double ln2 = Math.log(2.0);
        long bitSize = (long) Math.ceil(
                -expectedInsertions * Math.log(perSliceProbability)
                        / (ln2 * ln2)
        );

        // 根据最优公式 k = (m / n) * ln2 计算哈希函数数量，最少为 1
        int hashFunctions = Math.max(
                1,
                (int) Math.round(
                        bitSize / (double) expectedInsertions * ln2
                )
        );

        // 校验计算出的位数组大小不超过 Redis String 的 512 MiB 上限
        if (bitSize > MAX_REDIS_BITMAP_BITS) {
            throw new IllegalArgumentException("calculated bitmap exceeds Redis 512 MiB string limit: " + bitSize);
        }

        // 封装并返回所有计算结果
        return new BloomFilterParameters(
                expectedInsertions,
                overallFalsePositiveProbability,
                perSliceProbability,
                bitSize,
                hashFunctions
        );
    }
}
