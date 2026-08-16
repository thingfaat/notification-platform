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
                    arguments
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
        bloomDirty.set(true);
        long currentSlice = sliceWindow.currentSliceStart();
        try {
            // 历史片继续保留；只清理即将完整重建的当前片。
            redisTemplate.delete(List.of(
                    BLOOM_READY_KEY,
                    ShortLinkRedisKeys.bloomSlice(currentSlice)
            ));
            return true;
        } catch (RuntimeException exception) {
            bloomDirty.set(true);
            log.warn("begin time-sliced bloom rebuild failed", exception);
            clearBloomReadySafely();
            return false;
        }
    }

    @Override
    public void completeBloomRebuild(Collection<String> shortCodes) {
        bloomTrusted.set(false);
        bloomDirty.set(true);
        long currentSlice = sliceWindow.currentSliceStart();
        String currentSliceId = Long.toString(currentSlice);
        String bitmapKey = ShortLinkRedisKeys.bloomSlice(currentSlice);

        try {
            // 空数据集也要创建 Bitmap Key，Lua 才能区分“完整空片”和“片丢失”。
            redisTemplate.opsForValue().setBit(bitmapKey, 0, false);
            writeRebuildBatches(bitmapKey, shortCodes);
            redisTemplate.expire(bitmapKey, sliceWindow.bitmapTtl());

            redisTemplate.opsForZSet().add(
                    BLOOM_REGISTRY_KEY,
                    currentSliceId,
                    currentSlice
            );

            // ready 必须是最后一个关键写入。
            redisTemplate.opsForValue().set(
                    BLOOM_READY_KEY,
                    currentSliceId,
                    sliceWindow.readyTtl()
            );

            cleanupExpiredSlicesSafely();
            bloomDirty.set(false);
            bloomTrusted.set(true);
            log.info("time-sliced bloom rebuilt, slice={}, count={}", currentSliceId, shortCodes.size());
        } catch (RuntimeException exception) {
            bloomTrusted.set(false);
            bloomDirty.set(true);
            log.error("complete time-sliced bloom rebuild failed", exception);
            clearBloomReadySafely();
        }
    }

    private void writeRebuildBatches(
            String bitmapKey,
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
                setOffsets(bitmapKey, batchOffsets);
                batchOffsets.clear();
                codesInBatch = 0;
            }
        }

        if (!batchOffsets.isEmpty()) {
            setOffsets(bitmapKey, batchOffsets);
        }
    }

    private void setOffsets(String bitmapKey, Collection<String> offsets) {
        Long affectedBits = redisTemplate.execute(
                SET_BITS_SCRIPT,
                List.of(bitmapKey),
                offsets.toArray(String[]::new)
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
