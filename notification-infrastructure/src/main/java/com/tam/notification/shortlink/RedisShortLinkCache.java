package com.tam.notification.shortlink;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tam.notification.domain.shortlink.ShortLinkCache;
import com.tam.notification.domain.shortlink.ShortLinkCacheEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * redis实现短链中shortCode的缓存
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisShortLinkCache implements ShortLinkCache {

    private static final String KEY_PREFIX = "shortlink:redirect:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 获取
     *
     * @param shortCode
     * @return
     */
    @Override
    public Optional<ShortLinkCacheEntry> get(final String shortCode) {

        try {
            final var payload = redisTemplate.opsForValue().get(KEY_PREFIX + shortCode);

            if (payload == null) {
                return Optional.empty();
            }

            final var entry = objectMapper.readValue(payload, ShortLinkCacheEntry.class);

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

        String key = buildKey(shortCode);

        try {
            String payload = objectMapper.writeValueAsString(entry);
            redisTemplate.opsForValue().set(key, payload, ttl);
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
        String key = buildKey(shortCode);

        try {
            redisTemplate.delete(key);
        } catch (RuntimeException e) {
            log.warn("short link cache error, key={}", shortCode, e);
        }
    }

    private String buildKey(final String shortCode) {
        return KEY_PREFIX + shortCode;
    }
}
