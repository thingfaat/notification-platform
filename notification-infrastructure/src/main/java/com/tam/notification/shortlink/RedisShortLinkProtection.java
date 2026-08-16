package com.tam.notification.shortlink;

import com.tam.notification.domain.shortlink.ShortLinkNegativeReason;
import com.tam.notification.domain.shortlink.ShortLinkProtection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * redis布隆过滤器与负缓存实现
 */
@Slf4j
@Service
public class RedisShortLinkProtection implements ShortLinkProtection {

    private static final int REBUILD_BATCH_CODES = 500;
    // 布隆过滤器就绪key
    private static final String BLOOM_READY_KEY = ShortLinkRedisKeys.bloomReady();
    // 布隆过滤器key
    private static final String BLOOM_REGISTRY_KEY = ShortLinkRedisKeys.bloomSliceRegistry();

    /**
     * 小规模课程版本给重建锁 5 分钟租期。
     * <p>
     * 如果重建超过 5 分钟，后续批次和 ready 发布会因为 token 校验失败而终止，
     * 不会把不完整 Bitmap 发布出去。
     */
    private static final Duration REBUILD_LOCK_TTL = Duration.ofMinutes(5);

    private static final String BLOOM_REBUILD_LOCK_KEY =
            ShortLinkRedisKeys.bloomRebuildLock();

    /**
     * 保存本机当前重建的 token 和目标时间片。
     * <p>
     * token 用来证明“这个锁仍然属于我”；
     * sliceStart 防止一次重建跨越时间片后错误发布。
     */
    private final AtomicReference<RebuildContext> activeRebuild = new AtomicReference<>();

    private record RebuildContext(
            String token,
            long sliceStart,
            String bitmapKey
    ) {
    }

    /**
     * 布隆过滤器检查脚本
     * KEYS[1] 是ready，KEYS[2]是当前片，其余是历史片
     * ARGV[1] 是预期当前片，ARGV[2...] 是该短码的bit offset
     * 返回2表示状态不可信，java侧必须fail-open
     */
    private static final DefaultRedisScript<Long> CHECK_SLICES_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then
                return 2
            end

            if redis.call('EXISTS', KEYS[2]) == 0 then
                return 2
            end

            for keyIndex = 2, #KEYS do
                local allSet = 1
                for argIndex = 2, #ARGV do
                    if redis.call(
                        'GETBIT',
                        KEYS[keyIndex],
                        tonumber(ARGV[argIndex])
                    ) == 0 then
                        allSet = 0
                        break
                    end
                end
                if allSet == 1 then
                    return 1
                end
            end

            return 0
            """,
            Long.class
    );

    private static final DefaultRedisScript<Long> SET_BITS_SCRIPT = new DefaultRedisScript<>("""
            for index = 1, #ARGV do
                redis.call('SETBIT', KEYS[1], tonumber(ARGV[index]), 1)
            end
            return #ARGV
            """,
            Long.class
    );

    /**
     * 原子抢锁并进入重建状态
     * KEYS[1] = rebuild lock
     * KEYS[2] = ready
     * KEYS[3] = 当前 Bitmap
     * ARGV[1] = UUID token
     * ARGV[2] = lock TTL 毫秒
     */
    private static final DefaultRedisScript<Long> BEGIN_REBUILD_SCRIPT = new DefaultRedisScript<>("""
            local acquired = redis.call(
                'SET',
                KEYS[1],
                ARGV[1],
                'NX',
                'PX',
                ARGV[2]
            )

            if not acquired then
                return 0
            end

            -- 抢锁成功后才能撤销 ready 和删除当前片。
            redis.call('DEL', KEYS[2], KEYS[3])
            return 1
            """,
            Long.class
    );

    /**
     * 带所有权校验的批量写入
     * KEYS[1] = rebuild lock
     * KEYS[2] = 当前 Bitmap
     * ARGV[1] = token
     * ARGV[2] = Bitmap TTL
     * ARGV[3...] = bit offsets
     */
    private static final DefaultRedisScript<Long> SET_REBUILD_BITS_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then
                return -1
            end

            for index = 3, #ARGV do
                redis.call(
                    'SETBIT',
                    KEYS[2],
                    tonumber(ARGV[index]),
                    1
                )
            end

            -- 即使重建中途失败，残留的部分 Bitmap 最终也会过期。
            redis.call('PEXPIRE', KEYS[2], ARGV[2])
            return #ARGV - 2
            """,
            Long.class
    );

    /**
     * 原子发布 ready
     * KEYS[1] = rebuild lock
     * KEYS[2] = Bitmap
     * KEYS[3] = ZSET registry
     * KEYS[4] = ready
     * ARGV[1] = token
     * ARGV[2] = slice ID
     * ARGV[3] = Bitmap TTL
     * ARGV[4] = ready TTL
     */
    private static final DefaultRedisScript<Long> PUBLISH_REBUILD_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then
                return 0
            end

            -- 空数据集时才创建一个全 0 Bitmap。
            -- 如果增量写已经创建了 Bitmap，绝对不能清除 bit 0。
            if redis.call('EXISTS', KEYS[2]) == 0 then
                redis.call('SETBIT', KEYS[2], 0, 0)
            end

            redis.call('PEXPIRE', KEYS[2], ARGV[3])
            redis.call(
                'ZADD',
                KEYS[3],
                tonumber(ARGV[2]),
                ARGV[2]
            )

            -- ready 必须在所有关键数据写完后发布。
            redis.call(
                'SET',
                KEYS[4],
                ARGV[2],
                'PX',
                ARGV[4]
            )

            -- 发布成功后原子释放锁。
            redis.call('DEL', KEYS[1])
            return 1
            """,
            Long.class
    );

    /**
     * 只有 token 仍属于当前实例时，才允许撤销重建并释放锁。
     * <p>
     * 不能直接 DEL 锁：旧实例的锁过期后，另一个实例可能已经拿到了新锁。
     */
    private static final DefaultRedisScript<Long> ABORT_REBUILD_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then
                return 0
            end

            redis.call('DEL', KEYS[2], KEYS[3], KEYS[1])
            return 1
            """,
            Long.class
    );

    private final AtomicBoolean bloomTrusted = new AtomicBoolean(false);
    /**
     * 读写 Redis 失败后，旧 ready 可能对应一个遗漏了新短码的 Bitmap。
     * dirty 只能由完整重建成功清除，不能被一次 GET ready 清除。
     */
    private final AtomicBoolean bloomDirty = new AtomicBoolean(false);

    private final StringRedisTemplate redisTemplate;
    private final BloomFilterParameters parameters;
    private final BloomSliceWindow sliceWindow;
    private final Duration negativeTtl;
    private final Duration negativeJitter;

    public RedisShortLinkProtection(
            StringRedisTemplate redisTemplate,
            @Value("${notification.shortlink.bloom.expected-insertions:100000}") long expectedInsertions,
            @Value("${notification.shortlink.bloom.overall-false-positive-probability:0.01}") double overallFalsePositiveProbability,
            @Value("${notification.shortlink.bloom.slice-duration:PT6H}") Duration sliceDuration,
            @Value("${notification.shortlink.bloom.retained-slice-count:4}") int retainedSliceCount,
            @Value("${notification.shortlink.negative-cache.ttl:PT2M}") Duration negativeTtl,
            @Value("${notification.shortlink.negative-cache.jitter:PT30S}") Duration negativeJitter,
            @Qualifier("bloomClock") Clock clock
    ) {
        if (negativeTtl == null || negativeTtl.isZero() || negativeTtl.isNegative()) {
            throw new IllegalArgumentException("negativeTtl must be positive");
        }
        if (negativeJitter == null || negativeJitter.isNegative()) {
            throw new IllegalArgumentException("negativeJitter must not be negative");
        }

        this.redisTemplate = redisTemplate;
        this.parameters = BloomFilterParameters.calculate(
                expectedInsertions, // 预期插入量
                overallFalsePositiveProbability, // 误判率
                retainedSliceCount // 保留的片数
        );
        this.sliceWindow = new BloomSliceWindow(
                clock,
                sliceDuration,
                retainedSliceCount
        );
        this.negativeTtl = negativeTtl;
        this.negativeJitter = negativeJitter;

        log.info(
                "time-sliced bloom configured, expectedInsertions={}, bitSize={}, hashFunctions={}, perSliceFpp={}, overallFpp={}",
                parameters.expectedInsertions(),
                parameters.bitSize(),
                parameters.hashFunctions(),
                parameters.perSliceFalsePositiveProbability(),
                parameters.overallFalsePositiveProbability()
        );
    }

    @Override
    public Optional<ShortLinkNegativeReason> getNegative(final String shortCode) {
        try {
            String value = redisTemplate.opsForValue().get(
                    ShortLinkRedisKeys.negative(shortCode)
            );
            if (value == null) {
                return Optional.empty();
            }
            return Optional.of(ShortLinkNegativeReason.valueOf(value));
        } catch (IllegalArgumentException exception) {
            log.warn("invalid short-link negative cache, shortCode={}", shortCode, exception);
            evictNegative(shortCode);
            return Optional.empty();
        } catch (RuntimeException exception) {
            log.warn("read short-link negative cache failed, shortCode={}", shortCode, exception);
            return Optional.empty();
        }
    }

    @Override
    public void cacheNegative(final String shortCode, final ShortLinkNegativeReason reason) {
        try {
            redisTemplate.opsForValue().set(
                    ShortLinkRedisKeys.negative(shortCode),
                    reason.name(),
                    negativeTtl.plusMillis(randomJitterMillis())
            );
        } catch (RuntimeException exception) {
            log.warn("write short-link negative cache failed, shortCode={}", shortCode, exception);
        }
    }

    @Override
    public void evictNegative(final String shortCode) {
        try {
            redisTemplate.delete(ShortLinkRedisKeys.negative(shortCode));
        } catch (RuntimeException exception) {
            log.warn("evict short-link negative cache failed, shortCode={}", shortCode, exception);
        }
    }

    @Override
    public boolean isBloomReady() {
        if (bloomDirty.get()) {
            return false;
        }

        long currentSlice = sliceWindow.currentSliceStart();
        String currentValue = Long.toString(currentSlice);
        String currentBitmap = ShortLinkRedisKeys.bloomSlice(currentSlice);

        try {
            String readyValue = redisTemplate.opsForValue().get(BLOOM_READY_KEY);
            Boolean bitmapExists = redisTemplate.hasKey(currentBitmap);
            boolean trusted = currentValue.equals(readyValue) && bitmapExists;
            bloomTrusted.set(trusted);
            return trusted;
        } catch (RuntimeException exception) {
            bloomTrusted.set(false);
            bloomDirty.set(true);
            log.warn("refresh bloom trusted state failed", exception);
            return false;
        }
    }

    @Override
    public boolean mightContain(final String shortCode) {
        // 其他实例可能已经完成重建，因此本机false时先尝试恢复信任
        if (!bloomTrusted.get() && !isBloomReady()) {
            return true;
        }

        List<String> keys = new ArrayList<>();
        keys.add(BLOOM_READY_KEY);
        for (final var sliceStart : sliceWindow.retainedSliceStarts()) {
            keys.add(ShortLinkRedisKeys.bloomSlice(sliceStart));
        }

        String[] offsets = bloomOffsets(shortCode);
        String[] arguments = new String[offsets.length + 1];
        arguments[0] = Long.toString(sliceWindow.currentSliceStart());
        System.arraycopy(offsets, 0, arguments, 1, offsets.length);

        try {
            Long result = redisTemplate.execute(
                    CHECK_SLICES_SCRIPT,
                    keys,
                    (Object[]) arguments
            );
            if (result == null || result == 2L) {
                bloomTrusted.set(false);
                return true;
            }
            return result == 1L;
        } catch (RuntimeException exception) {
            bloomTrusted.set(false);
            bloomDirty.set(true);
            log.warn("check time-sliced bloom failed, shortCode={}", shortCode, exception);
            return true;
        }
    }

    @Override
    public void addToBloom(final String shortCode) {
        long currentSlice = sliceWindow.currentSliceStart();
        String bitmapKey = ShortLinkRedisKeys.bloomSlice(currentSlice);
        try {
            setOffsets(bitmapKey, List.of(bloomOffsets(shortCode)));
            redisTemplate.expire(bitmapKey, sliceWindow.bitmapTtl());
        } catch (RuntimeException exception) {
            bloomTrusted.set(false);
            bloomDirty.set(true);
            log.warn("add short code to current bloom slice failed, shortCode={}",
                    shortCode, exception);
            clearBloomReadySafely();
        }
    }

    @Override
    public boolean beginBloomRebuild() {
        bloomTrusted.set(false);

        long currentSlice = sliceWindow.currentSliceStart();
        String bitmapKey = ShortLinkRedisKeys.bloomSlice(currentSlice);
        RebuildContext context = new RebuildContext(
                UUID.randomUUID().toString(),
                currentSlice,
                bitmapKey
        );

        // 除了分布式锁，再防止本机调用者绕过初始化器重复发起重建。
        if (!activeRebuild.compareAndSet(null, context)) {
            return false;
        }

        try {
            Long acquired = redisTemplate.execute(
                    BEGIN_REBUILD_SCRIPT,
                    List.of(
                            BLOOM_REBUILD_LOCK_KEY,
                            BLOOM_READY_KEY,
                            bitmapKey
                    ),
                    context.token(),
                    Long.toString(REBUILD_LOCK_TTL.toMillis())
            );

            if (acquired == null || acquired != 1L) {
                activeRebuild.compareAndSet(context, null);
                return false;
            }

            // 抢锁成功且 ready 已撤销；成功发布前，本机只能 fail-open。
            bloomDirty.set(true);
            return true;
        } catch (RuntimeException exception) {
            log.warn("begin time-sliced bloom rebuild failed", exception);

            /*
             * Lua 可能已经执行成功，只是响应在网络中丢失。
             * 使用 token 中止既能释放自己的锁，也不会删除其他实例的新锁。
             */
            abortBloomRebuild(context);
            return false;
        }
    }

    @Override
    public boolean completeBloomRebuild(Collection<String> shortCodes) {
        bloomTrusted.set(false);
        bloomDirty.set(true);

        RebuildContext context = activeRebuild.get();
        if (context == null) {
            log.warn("complete bloom rebuild called without active context");
            return false;
        }

        long actualSlice = sliceWindow.currentSliceStart();
        if (actualSlice != context.sliceStart()) {
            log.warn(
                    "bloom slice changed during rebuild, expected={}, actual={}",
                    context.sliceStart(),
                    actualSlice
            );
            abortBloomRebuild(context);
            return false;
        }

        String currentSliceId = Long.toString(context.sliceStart());

        try {
            writeRebuildBatches(context, shortCodes);

            Long published = redisTemplate.execute(
                    PUBLISH_REBUILD_SCRIPT,
                    List.of(
                            BLOOM_REBUILD_LOCK_KEY,
                            context.bitmapKey(),
                            BLOOM_REGISTRY_KEY,
                            BLOOM_READY_KEY
                    ),
                    context.token(),
                    currentSliceId,
                    Long.toString(sliceWindow.bitmapTtl().toMillis()),
                    Long.toString(sliceWindow.readyTtl().toMillis())
            );

            if (published == null || published != 1L) {
                log.warn("publish bloom rebuild rejected because lock ownership was lost");
                abortBloomRebuild(context);
                return false;
            }

            // 发布 Lua 已经原子释放 Redis 锁，这里只清理本机上下文。
            activeRebuild.compareAndSet(context, null);
            cleanupExpiredSlicesSafely();
            bloomDirty.set(false);
            bloomTrusted.set(true);
            log.info("time-sliced bloom rebuilt, slice={}, count={}", currentSliceId, shortCodes.size());
            return true;
        } catch (RuntimeException exception) {
            bloomTrusted.set(false);
            bloomDirty.set(true);
            log.error("complete time-sliced bloom rebuild failed", exception);
            abortBloomRebuild(context);
            return false;
        }
    }

    @Override
    public void abortBloomRebuild() {
        RebuildContext context = activeRebuild.get();
        if (context != null) {
            abortBloomRebuild(context);
        }
    }

    private void abortBloomRebuild(RebuildContext context) {
        if (!activeRebuild.compareAndSet(context, null)) {
            return;
        }

        bloomTrusted.set(false);
        bloomDirty.set(true);

        try {
            redisTemplate.execute(
                    ABORT_REBUILD_SCRIPT,
                    List.of(
                            BLOOM_REBUILD_LOCK_KEY,
                            BLOOM_READY_KEY,
                            context.bitmapKey()
                    ),
                    context.token()
            );
        } catch (RuntimeException exception) {
            // 无法释放时依靠锁 TTL；绝不能无条件删除可能属于其他实例的锁。
            log.warn("abort bloom rebuild failed; lock TTL will recover it", exception);
        }
    }

    private void writeRebuildBatches(
            RebuildContext context,
            Collection<String> shortCodes
    ) {
        List<String> batchOffsets = new ArrayList<>(
                REBUILD_BATCH_CODES * parameters.hashFunctions()
        );
        int codesInBatch = 0;

        for (String shortCode : shortCodes) {
            if (shortCode == null || shortCode.isBlank()) {
                continue;
            }
            batchOffsets.addAll(List.of(bloomOffsets(shortCode)));
            codesInBatch++;

            if (codesInBatch == REBUILD_BATCH_CODES) {
                setRebuildOffsets(context, batchOffsets);
                batchOffsets.clear();
                codesInBatch = 0;
            }
        }

        if (!batchOffsets.isEmpty()) {
            setRebuildOffsets(context, batchOffsets);
        }
    }

    private void setRebuildOffsets(
            RebuildContext context,
            Collection<String> offsets
    ) {
        List<String> arguments = new ArrayList<>(offsets.size() + 2);
        arguments.add(context.token());
        arguments.add(Long.toString(sliceWindow.bitmapTtl().toMillis()));
        arguments.addAll(offsets);

        Long affectedBits = redisTemplate.execute(
                SET_REBUILD_BITS_SCRIPT,
                List.of(
                        BLOOM_REBUILD_LOCK_KEY,
                        context.bitmapKey()
                ),
                arguments.toArray()
        );

        if (affectedBits == null || affectedBits.longValue() != offsets.size()) {
            throw new IllegalStateException(
                    "bloom rebuild lock lost or bit write failed"
            );
        }
    }

    private void setOffsets(String bitmapKey, Collection<String> offsets) {
        Long affectedBits = redisTemplate.execute(
                SET_BITS_SCRIPT,
                List.of(bitmapKey),
                offsets.toArray()
        );
        if (affectedBits == null || affectedBits.longValue() != offsets.size()) {
            throw new IllegalStateException("set bloom bits returned unexpected result");
        }
    }

    private void cleanupExpiredSlicesSafely() {
        try {
            long oldestRetained = sliceWindow.oldestRetainedSliceStart();
            Set<String> expiredSliceIds = redisTemplate.opsForZSet().rangeByScore(
                    BLOOM_REGISTRY_KEY,
                    Double.NEGATIVE_INFINITY,
                    oldestRetained - 1.0
            );
            if (expiredSliceIds == null || expiredSliceIds.isEmpty()) {
                return;
            }

            List<String> expiredBitmapKeys = expiredSliceIds.stream()
                    .map(Long::parseLong)
                    .map(ShortLinkRedisKeys::bloomSlice)
                    .toList();

            redisTemplate.delete(expiredBitmapKeys);
            redisTemplate.opsForZSet().remove(
                    BLOOM_REGISTRY_KEY,
                    expiredSliceIds.toArray()
            );
            log.info("expired bloom slices cleaned, count={}", expiredSliceIds.size());
        } catch (RuntimeException exception) {
            // TTL 仍会兜底，清理失败不能让完整的当前片变得不可用。
            log.warn("cleanup expired bloom slices failed", exception);
        }
    }

    private void clearBloomReadySafely() {
        try {
            redisTemplate.delete(BLOOM_READY_KEY);
        } catch (RuntimeException cleanupException) {
            log.warn("clear bloom ready flag failed", cleanupException);
        }
    }

    private String[] bloomOffsets(String shortCode) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(
                    shortCode.getBytes(StandardCharsets.UTF_8)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }

        ByteBuffer buffer = ByteBuffer.wrap(digest);
        long firstHash = buffer.getLong();
        long secondHash = buffer.getLong();
        if (secondHash == 0L) {
            secondHash = 0x9E3779B97F4A7C15L;
        }

        String[] offsets = new String[parameters.hashFunctions()];
        for (int index = 0; index < offsets.length; index++) {
            long combinedHash = firstHash + index * secondHash;
            offsets[index] = Long.toString(
                    Math.floorMod(combinedHash, parameters.bitSize())
            );
        }
        return offsets;
    }

    private long randomJitterMillis() {
        long upperBound = negativeJitter.toMillis();
        return upperBound <= 0L
                ? 0L
                : ThreadLocalRandom.current().nextLong(upperBound + 1L);
    }
}
