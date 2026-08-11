package com.tam.notification.shortlink;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tam.notification.domain.shortlink.ShortLinkCache;
import com.tam.notification.domain.shortlink.ShortLinkCacheEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * redis实现短链中shortCode的缓存
 */
@Slf4j
@Service
public class RedisShortLinkCache implements ShortLinkCache {

    private static final String KEY_PREFIX = "shortlink:redirect:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Cache<String, ShortLinkCacheEntry> localCache;

    public RedisShortLinkCache(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${notification.shortlink.hot-cache.max-size:10000}") long maximumSize,
            @Value("${notification.shortlink.hot-cache.ttl:PT1M}") Duration localTtl
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.localCache = Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfterWrite(localTtl)
                .build();
    }

    /**
     * 获取
     *
     * @param shortCode
     * @return
     */
    @Override
    public Optional<ShortLinkCacheEntry> get(final String shortCode) {

        final var local = localCache.getIfPresent(shortCode);

        // 本地存在直接返回
        if (local != null) {
            return Optional.of(local);
        }

        // 本地缓存不存在，查询redis缓存
        try {
            final var payload = redisTemplate.opsForValue().get(KEY_PREFIX + shortCode);

            if (payload == null) {
                return Optional.empty();
            }

            final var entry = objectMapper.readValue(payload, ShortLinkCacheEntry.class);

            localCache.put(shortCode, entry);

            return Optional.of(entry);
        } catch (JsonProcessingException e) {
            log.warn("short link cache payload is invalid, key={}", shortCode, e);
            evict(shortCode);
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("short link cache error, key={}", shortCode, e);
            return Optional.empty();
        }
    }

    /**
     * 添加
     *
     * @param shortCode
     * @param entry
     * @param ttl
     */
    @Override
    public void put(final String shortCode, final ShortLinkCacheEntry entry, final Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return;
        }

        // redis异常时，本机热点缓存仍可提供短时间降级能力
        localCache.put(shortCode, entry);

        try {
            String payload = objectMapper.writeValueAsString(entry);
            redisTemplate.opsForValue().set(buildKey(shortCode), payload, ttl);
        } catch (JsonProcessingException e) {
            log.warn("short link cache payload is invalid, key={}", shortCode, e);
        } catch (RuntimeException e) {
            log.warn("short link cache error, key={}", shortCode, e);
        }
    }

    /**
     * 删除
     *
     * @param shortCode
     */
    @Override
    public void evict(final String shortCode) {
        localCache.invalidate(shortCode);

        try {
            redisTemplate.delete(buildKey(shortCode));
        } catch (RuntimeException e) {
            log.warn("short link cache error, key={}", shortCode, e);
        }
    }

    private String buildKey(final String shortCode) {
        return KEY_PREFIX + shortCode;
    }
}
