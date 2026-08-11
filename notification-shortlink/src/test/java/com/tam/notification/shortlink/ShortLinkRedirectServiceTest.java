package com.tam.notification.shortlink;

import com.tam.notification.common.tenant.TenantContext;
import com.tam.notification.domain.enums.ShortLinkStatus;
import com.tam.notification.domain.shortlink.*;
import com.tam.notification.shortlink.dto.ResolvedShortLink;
import com.tam.notification.shortlink.exception.ShortLinkExpiredException;
import com.tam.notification.shortlink.exception.ShortLinkNotFoundException;
import com.tam.notification.shortlink.service.ShortLinkRedirectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ShortLinkRedirectServiceTest {

    private static final String SHORT_CODE = "aZ8k2LmP";

    @Mock
    private ShortLinkCache shortLinkCache;

    @Mock
    private ShortLinkMappingRepository mappingRepository;

    @Mock
    private ShortLinkRepository shortLinkRepository;

    @InjectMocks
    private ShortLinkRedirectService redirectService;

    @Mock
    private ShortLinkProtection shortLinkProtection;

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void shouldReturnDirectlyWhenCacheHits() {
        ShortLinkCacheEntry cached =
                new ShortLinkCacheEntry(
                        10001L,
                        30001L,
                        "https://example.com/orders/1",
                        LocalDateTime.now().plusMinutes(10)
                );

        when(shortLinkCache.get(SHORT_CODE))
                .thenReturn(Optional.of(cached));

        ResolvedShortLink result = redirectService.resolve(SHORT_CODE);

        assertEquals(
                "https://example.com/orders/1",
                result.originalUrl()
        );

        verifyNoInteractions(
                mappingRepository,
                shortLinkRepository
        );
    }

    @Test
    void shouldFallbackToDatabaseAndRestoreTenantContext() {
        when(shortLinkCache.get(SHORT_CODE))
                .thenReturn(Optional.empty());

        // 1. 正缓存未命中
        when(shortLinkCache.get(SHORT_CODE))
                .thenReturn(Optional.empty());

        // 2. 负缓存也未命中
        when(shortLinkProtection.getNegative(SHORT_CODE))
                .thenReturn(Optional.empty());

        // 3. 布隆过滤器认为短码“可能存在”，允许继续查询数据库
        when(shortLinkProtection.mightContain(SHORT_CODE))
                .thenReturn(true);

        ShortLinkMapping mapping = new ShortLinkMapping();

        mapping.setTenantId(10001L);
        mapping.setShortLinkId(30001L);
        mapping.setShortCode(SHORT_CODE);

        when(mappingRepository
                .findByShortCodeAcrossTenants(SHORT_CODE))
                .thenReturn(Optional.of(mapping));

        ShortLink shortLink = new ShortLink();
        shortLink.setId(30001L);
        shortLink.setTenantId(10001L);
        shortLink.setApplicationId(20001L);
        shortLink.setOriginalUrl(
                "https://example.com/orders/1"
        );
        shortLink.setExpireAt(
                LocalDateTime.now().plusMinutes(10)
        );
        shortLink.setStatus(ShortLinkStatus.ACTIVE);

        when(shortLinkRepository.findById(30001L))
                .thenAnswer(invocation -> {
                    assertEquals(
                            10001L,
                            TenantContext.requireTenantId()
                    );

                    return Optional.of(shortLink);
                });

        TenantContext.setTenantId(99999L);

        ResolvedShortLink result =
                redirectService.resolve(SHORT_CODE);

        assertEquals(
                "https://example.com/orders/1",
                result.originalUrl()
        );

        assertEquals(
                99999L,
                TenantContext.requireTenantId()
        );

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);

        verify(shortLinkCache).put(
                eq(SHORT_CODE),
                any(ShortLinkCacheEntry.class),
                ttlCaptor.capture()
        );

        assertTrue(!ttlCaptor.getValue().isNegative());
        assertTrue(
                ttlCaptor.getValue()
                        .compareTo(Duration.ofMinutes(30))
                        <= 0
        );
    }

    @Test
    void shouldRejectExpiredShortLink() {
        when(shortLinkCache.get(SHORT_CODE))
                .thenReturn(Optional.empty());

        // 1. 正缓存未命中
        when(shortLinkCache.get(SHORT_CODE))
                .thenReturn(Optional.empty());

        // 2. 负缓存未命中
        when(shortLinkProtection.getNegative(SHORT_CODE))
                .thenReturn(Optional.empty());

        // 3. 布隆过滤器放行，之后才能从数据库查到过期短链
        when(shortLinkProtection.mightContain(SHORT_CODE))
                .thenReturn(true);

        ShortLinkMapping mapping = new ShortLinkMapping();

        mapping.setTenantId(10001L);
        mapping.setShortLinkId(30001L);

        when(mappingRepository
                .findByShortCodeAcrossTenants(SHORT_CODE))
                .thenReturn(Optional.of(mapping));

        ShortLink shortLink = new ShortLink();
        shortLink.setId(30001L);
        shortLink.setTenantId(10001L);
        shortLink.setOriginalUrl(
                "https://example.com/orders/1"
        );
        shortLink.setExpireAt(
                LocalDateTime.now().minusSeconds(1)
        );
        shortLink.setStatus(ShortLinkStatus.ACTIVE);

        when(shortLinkRepository.findById(30001L))
                .thenReturn(Optional.of(shortLink));

        assertThrows(
                ShortLinkExpiredException.class,
                () -> redirectService.resolve(SHORT_CODE)
        );

        verify(shortLinkCache, never()).put(
                any(),
                any(),
                any()
        );
    }

    @Test
    void shouldRejectWhenNegativeCacheHits() {
        when(shortLinkCache.get(SHORT_CODE))
                .thenReturn(Optional.empty());

        when(shortLinkProtection.getNegative(SHORT_CODE))
                .thenReturn(Optional.of(
                        ShortLinkNegativeReason.NOT_FOUND
                ));

        assertThrows(
                ShortLinkNotFoundException.class,
                () -> redirectService.resolve(SHORT_CODE)
        );

        verify(shortLinkProtection, never())
                .mightContain(anyString());

        verifyNoInteractions(
                mappingRepository,
                shortLinkRepository
        );
    }

    @Test
    void shouldRejectWhenBloomSaysDefinitelyMissing() {
        when(shortLinkCache.get(SHORT_CODE))
                .thenReturn(Optional.empty());

        when(shortLinkProtection.getNegative(SHORT_CODE))
                .thenReturn(Optional.empty());

        when(shortLinkProtection.mightContain(SHORT_CODE))
                .thenReturn(false);

        assertThrows(
                ShortLinkNotFoundException.class,
                () -> redirectService.resolve(SHORT_CODE)
        );

        verify(shortLinkProtection).cacheNegative(
                SHORT_CODE,
                ShortLinkNegativeReason.NOT_FOUND
        );

        verifyNoInteractions(
                mappingRepository,
                shortLinkRepository
        );
    }

    @Test
    void shouldCacheExpiredReasonWhenCachedEntryExpired() {
        ShortLinkCacheEntry cached =
                new ShortLinkCacheEntry(
                        10001L,
                        30001L,
                        "https://example.com/orders/1",
                        LocalDateTime.now().minusSeconds(1)
                );

        when(shortLinkCache.get(SHORT_CODE))
                .thenReturn(Optional.of(cached));

        assertThrows(
                ShortLinkExpiredException.class,
                () -> redirectService.resolve(SHORT_CODE)
        );

        verify(shortLinkCache).evict(SHORT_CODE);

        verify(shortLinkProtection).cacheNegative(
                SHORT_CODE,
                ShortLinkNegativeReason.EXPIRED
        );

        verifyNoInteractions(
                mappingRepository,
                shortLinkRepository
        );
    }

    @Test
    void shouldCacheNotFoundWhenDatabaseMisses() {
        when(shortLinkCache.get(SHORT_CODE))
                .thenReturn(Optional.empty());

        when(shortLinkProtection.getNegative(SHORT_CODE))
                .thenReturn(Optional.empty());

        when(shortLinkProtection.mightContain(SHORT_CODE))
                .thenReturn(true);

        when(mappingRepository
                .findByShortCodeAcrossTenants(SHORT_CODE))
                .thenReturn(Optional.empty());

        assertThrows(
                ShortLinkNotFoundException.class,
                () -> redirectService.resolve(SHORT_CODE)
        );

        verify(shortLinkProtection).cacheNegative(
                SHORT_CODE,
                ShortLinkNegativeReason.NOT_FOUND
        );

        verifyNoInteractions(shortLinkRepository);
    }
}
