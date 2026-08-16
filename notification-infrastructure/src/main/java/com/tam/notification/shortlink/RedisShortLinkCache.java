package com.tam.notification.shortlink;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tam.notification.domain.shortlink.ShortLinkCache;
import com.tam.notification.domain.shortlink.ShortLinkCacheEntry;
import com.tam.notification.redis.RedisClusterSlot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

/**
 * redis实现短链中shortCode的缓存
 */
@Slf4j
@Service
public class RedisShortLinkCache implements ShortLinkCache {

    // 限制单次响应体和节点占用时间，避免一个超大的MGET形成新的尖峰
    private static final int MAX_MGET_KEYS = 100;

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

        final var redisKey = ShortLinkRedisKeys.redirect(shortCode);
        // 本地缓存不存在，查询redis缓存
        try {
            final var payload = redisTemplate.opsForValue().get(redisKey);
            if (payload == null) {
                return Optional.empty();
            }

            final var entry = deserialize(redisKey, payload);
            localCache.put(shortCode, entry);
            return Optional.of(entry);
        } catch (JsonProcessingException e) {
            log.warn("short link cache payload is invalid, key={}", shortCode, e);
            evict(shortCode);
            return Optional.empty();
        } catch (RuntimeException e) {
            // redis故障只造成缓存未命中，调用方继续回源 mysql
            log.warn("short link cache error, key={}", shortCode, e);
            return Optional.empty();
        }
    }

    /**
     * 批量获取
     * @param shortCodes
     * @return
     */
    @Override
    public Map<String, ShortLinkCacheEntry> getAll(final Collection<String> shortCodes) {
        if (shortCodes == null || shortCodes.isEmpty()) {
            return Map.of();
        }

        // LinkHashSet 同时完成去重和输入顺序保留，空值不是合法短码，直接跳过，避免一个坏参数拖垮整批
        LinkedHashSet<String> distinctCodes = new LinkedHashSet<>();
        for (final var shortCode : shortCodes) {
            if (shortCode != null && !shortCode.isBlank()) {
                distinctCodes.add(shortCode);
            }
        }

        Map<String, ShortLinkCacheEntry> result = new LinkedHashMap<>();
        List<String> missedCodes = new ArrayList<>();
        for (final var shortCode : distinctCodes) {
            ShortLinkCacheEntry local = localCache.getIfPresent(shortCode);
            if (local == null) {
                missedCodes.add(shortCode);
            } else {
                result.put(shortCode, local);
            }
        }

        // 必须按“真实 redis key”计算槽位，不能只对shortCode计算，当前 hash tag使结果一致，但使用真实key能防止未来改前缀后日志失真
        // 本地缓存缺少的，按照槽位分组
        Map<Integer, List<String>> codesBySlot = new LinkedHashMap<>();
        for (final var shortCode : missedCodes) {
            String redisKey = ShortLinkRedisKeys.redirect(shortCode);
            int slot = RedisClusterSlot.slot(redisKey);
            codesBySlot.computeIfAbsent(slot, ignored -> new ArrayList<>())
                    .add(shortCode);
        }

        for (final var group : codesBySlot.entrySet()) {
            List<String> codesInSlot = group.getValue();

            // 同槽只能命令能够执行的前提，不代表一批就可以无限大，再按100个一组切片，控制单条命令的响应大小和执行时间
            for (int from = 0; from < codesInSlot.size(); from += MAX_MGET_KEYS) {
                int to = Math.min(from + MAX_MGET_KEYS, codesInSlot.size());
                readOneSlot(
                        group.getKey(),
                        codesInSlot.subList(from, to),
                        result
                );
            }
        }
        return result;
    }

    /**
     * 一个MGET只处理一个槽位，避免CROSSSLOT
     * CROSSSLOT：在同一个命令中，访问不同槽位意味着可能需要路由到不同redis节点，
     * 多 Key 原子命令（MGET/MSET 等）硬性约束：所有 key 必须落在同一个 slot
     *
     * @param slot
     * @param shortCodes
     * @param result
     */
    private void readOneSlot(
            int slot,
            List<String> shortCodes,
            Map<String, ShortLinkCacheEntry> result
    ) {
        List<String> redisKeys = shortCodes.stream()
                .map(ShortLinkRedisKeys::redirect)
                .toList();

        try {
            List<String> payloads = redisTemplate
                    .opsForValue()
                    .multiGet(redisKeys);

            if (payloads == null) {
                return;
            }

            for (int index = 0; index < shortCodes.size(); index++) {
                String payload = payloads.get(index);
                if (payload == null) {
                    continue;
                }

                String shortCode = shortCodes.get(index);
                String redisKey = redisKeys.get(index);

                try {
                    ShortLinkCacheEntry entry = deserialize(redisKey, payload);
                    localCache.put(shortCode, entry);
                    result.put(shortCode, entry);
                } catch (JsonProcessingException exception) {
                    log.warn(
                            "short link cache payload is invalid, key={}",
                            redisKey,
                            exception
                    );
                    evict(shortCode);
                }
            }
        } catch (RuntimeException e) {
            /*
             * 单个槽位失败不影响其他槽位；未返回的部分由上层回源数据库。
             * 不在这里并发访问所有槽，避免把公共线程池和集群同时打满。
             */
            log.warn(
                    "batch read short link cache failed, slot={}, size={}",
                    slot,
                    shortCodes.size(),
                    e
            );
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
    public void put(
            final String shortCode,
            final ShortLinkCacheEntry entry,
            final Duration ttl
    ) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return;
        }

        // redis异常时，本机热点缓存仍可提供短时间降级能力
        localCache.put(shortCode, entry);

        String redisKey = ShortLinkRedisKeys.redirect(shortCode);

        try {
            String payload = objectMapper.writeValueAsString(entry);
            redisTemplate.opsForValue().set(redisKey, payload, ttl);
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

        String redisKey = ShortLinkRedisKeys.redirect(shortCode);

        try {
            redisTemplate.delete(redisKey);
        } catch (RuntimeException e) {
            log.warn("short link cache error, key={}", shortCode, e);
        }
    }

    private ShortLinkCacheEntry deserialize(
            String redisKey,
            String payload
    ) throws JsonProcessingException {
        return objectMapper.readValue(
                payload,
                ShortLinkCacheEntry.class
        );
    }
}
