package com.tam.notification.shortlink;

import com.tam.notification.common.tenant.TenantContext;
import com.tam.notification.domain.enums.ShortLinkStatus;
import com.tam.notification.domain.shortlink.*;
import com.tam.notification.shortlink.dto.ResolvedShortLink;
import com.tam.notification.shortlink.exception.ShortLinkExpiredException;
import com.tam.notification.shortlink.service.ShortLinkRedirectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

    @Mock
    private ShortLinkRedirectService redirectService;

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
}
