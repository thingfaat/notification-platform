package com.tam.notification.shortlink;

import com.tam.notification.domain.shortlink.ShortLinkNegativeReason;
import com.tam.notification.domain.shortlink.ShortLinkProtection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * redis布隆过滤器与负缓存实现
 */
@Slf4j
@Service
public class RedisShortLinkProtection implements ShortLinkProtection {
    // 负缓存key前缀
    private static final String NEGATIVE_KEY_PREFIX = "shortlink:redirect:negative:";
    // 布隆过滤器key
    private static final String BLOOM_BITMAP_KEY = "shortlink:bloom:codes:v1";
    // 布隆过滤器就绪key
    private static final String BLOOM_READY_KEY = "shortlink:bloom:ready:v1";
    // 布隆过滤器检查脚本
    private static final DefaultRedisScript<Long> CHECK_BITS_SCRIPT = new DefaultRedisScript<>(
            """
                    if redis.call('GET', KEYS[1]) ~= '1' then
                        return 1
                    end
                    
                    for i = 1, #ARGV do
                        if redis.call(
                            'GETBIT',
                            KEYS[2],
                            tonumber(ARGV[i])
                        ) == 0 then
                            return 0
                        end
                    end
                    
                    return 1
                    """,
            Long.class
    );
    // 设置位脚本
    private static final DefaultRedisScript<Long> SET_BITS_SCRIPT = new DefaultRedisScript<>(
            """
                    for i = 1, #ARGV do
                        redis.call(
                            'SETBIT',
                            KEYS[1],
                            tonumber(ARGV[i]),
                            1
                        )
                    end
                    
                    return #ARGV
                    """,
            Long.class
    );

    private final StringRedisTemplate redisTemplate;
    private final long bloomBitSize;
    private final int bloomHashFunctions;
    private final Duration negativeTtl;
    private final Duration negativeJitter;

    public RedisShortLinkProtection(
            StringRedisTemplate redisTemplate,
            @Value("${notification.shortlink.bloom.bit-size:16777216}") long bloomBitSize,
            @Value("${notification.shortlink.bloom.hash-functions:7}") int bloomHashFunctions,
            @Value("${notification.shortlink.negative-cache.ttl:PT2M}") Duration negativeTtl,
            @Value("${notification.shortlink.negative-cache.jitter:PT30S}") Duration negativeJitter
    ) {
        if (bloomBitSize <= 0) {
            throw new IllegalArgumentException("bloomBitSize must be positive");
        }

        if (bloomHashFunctions <= 0) {
            throw new IllegalArgumentException("bloomHashFunctions must be positive");
        }

        if (negativeTtl.isZero() || negativeTtl.isNegative()) {
            throw new IllegalArgumentException("negativeTtl must be positive");
        }

        if (negativeJitter.isNegative()) {
            throw new IllegalArgumentException("negativeJitter must not be negative");
        }

        this.redisTemplate = redisTemplate;
        this.bloomBitSize = bloomBitSize;
        this.bloomHashFunctions = bloomHashFunctions;
        this.negativeTtl = negativeTtl;
        this.negativeJitter = negativeJitter;
    }

    @Override
    public Optional<ShortLinkNegativeReason> getNegative(final String shortCode) {
        try {
            final var value = redisTemplate.opsForValue().get(negativeKey(shortCode));
            if (value == null) {
                return Optional.empty();
            }

            return Optional.of(ShortLinkNegativeReason.valueOf(value));
        } catch (IllegalArgumentException e) {
            log.warn(
                    "invalid short-link negative cache, shortCode={}",
                    shortCode,
                    e
            );
            evictNegative(shortCode);
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn(
                    "read short-link negative cache failed, shortCode={}",
                    shortCode,
                    e
            );
            return Optional.empty();
        }
    }

    @Override
    public void cacheNegative(final String shortCode, final ShortLinkNegativeReason reason) {
        try {
            redisTemplate.opsForValue().set(
                    negativeKey(shortCode),
                    reason.name(),
                    negativeTtl.plusMillis(randomJitterMillis())
            );
        } catch (RuntimeException exception) {
            log.warn(
                    "write short-link negative cache failed, shortCode={}",
                    shortCode,
                    exception
            );
        }
    }

    @Override
    public void evictNegative(final String shortCode) {
        try {
            redisTemplate.delete(negativeKey(shortCode));
        } catch (RuntimeException exception) {
            log.warn(
                    "evict short-link negative cache failed, shortCode={}",
                    shortCode,
                    exception
            );
        }
    }

    @Override
    public boolean mightContain(final String shortCode) {
        try {
            Long result = redisTemplate.execute(
                    CHECK_BITS_SCRIPT,
                    List.of(
                            BLOOM_READY_KEY,
                            BLOOM_BITMAP_KEY
                    ),
                    bloomOffsets(shortCode)
            );

            // Redis 返回异常值时放行数据库，避免误伤合法短链。
            return result == null || result == 1L;
        } catch (RuntimeException exception) {
            log.warn(
                    "check short-link bloom filter failed, shortCode={}",
                    shortCode,
                    exception
            );
            return true;
        }
    }

    @Override
    public void addToBloom(final String shortCode) {
        try {
            setBloomBits(shortCode);
        } catch (RuntimeException exception) {
            log.warn(
                    "add short code to bloom filter failed, shortCode={}",
                    shortCode,
                    exception
            );
        }
    }

    @Override
    public void rebuildBloom(final Collection<String> shortCodes) {
        try {
            // ready 不存在时，mightContain 会 fail-open。
            redisTemplate.delete(BLOOM_READY_KEY);
            redisTemplate.delete(BLOOM_BITMAP_KEY);

            for (String shortCode : shortCodes) {
                setBloomBits(shortCode);
            }

            redisTemplate.opsForValue().set(
                    BLOOM_READY_KEY,
                    "1"
            );

            log.info(
                    "short-link bloom filter rebuilt, count={}",
                    shortCodes.size()
            );
        } catch (RuntimeException exception) {
            log.error(
                    "rebuild short-link bloom filter failed",
                    exception
            );

            try {
                redisTemplate.delete(BLOOM_READY_KEY);
            } catch (RuntimeException cleanupException) {
                log.warn(
                        "clear bloom ready flag failed",
                        cleanupException
                );
            }
        }
    }

    private void setBloomBits(String shortCode) {
        redisTemplate.execute(
                SET_BITS_SCRIPT,
                List.of(BLOOM_BITMAP_KEY),
                bloomOffsets(shortCode)
        );
    }

    private String[] bloomOffsets(String shortCode) {
        byte[] digest;

        try {
            digest = MessageDigest
                    .getInstance("SHA-256")
                    .digest(
                            shortCode.getBytes(
                                    StandardCharsets.UTF_8
                            )
                    );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    exception
            );
        }

        ByteBuffer buffer = ByteBuffer.wrap(digest);
        long firstHash = buffer.getLong();
        long secondHash = buffer.getLong();

        if (secondHash == 0L) {
            secondHash = 0x9E3779B97F4A7C15L;
        }

        String[] offsets = new String[bloomHashFunctions];

        for (int index = 0; index < bloomHashFunctions; index++) {

            long combinedHash = firstHash + index * secondHash;

            offsets[index] = Long.toString(
                    Math.floorMod(
                            combinedHash,
                            bloomBitSize
                    )
            );
        }

        return offsets;
    }

    private long randomJitterMillis() {
        long upperBound = negativeJitter.toMillis();

        if (upperBound <= 0L) {
            return 0L;
        }

        return ThreadLocalRandom.current()
                .nextLong(upperBound + 1L);
    }

    private String negativeKey(String shortCode) {
        return NEGATIVE_KEY_PREFIX + shortCode;
    }
}
