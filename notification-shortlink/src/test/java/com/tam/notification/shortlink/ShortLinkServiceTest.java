package com.tam.notification.shortlink;

import com.tam.notification.domain.application.Application;
import com.tam.notification.domain.application.ApplicationRepository;
import com.tam.notification.domain.enums.ShortLinkStatus;
import com.tam.notification.domain.shortlink.*;
import com.tam.notification.shortlink.dto.CreateShortLinkCommand;
import com.tam.notification.shortlink.dto.CreatedShortLink;
import com.tam.notification.shortlink.service.ShortLinkService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ShortLinkServiceTest {
    @Mock
    private ApplicationRepository applicationRepository;

    @Mock
    private ShortLinkRepository shortLinkRepository;

    @Mock
    private ShortLinkMappingRepository mappingRepository;

    @Mock
    private ShortCodeGenerator shortCodeGenerator;

    @InjectMocks
    private ShortLinkService shortLinkService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Test
    void shouldRetryWhenFirstShortCodeCollides() {
        LocalDateTime expireAt = LocalDateTime
                .now()
                .plusDays(7)
                .withNano(123_000_000);

        when(shortLinkRepository.findByIdempotencyKey(
                20001L,
                ShortLinkBusinessType.MANAGEMENT,
                "req-001"
        )).thenReturn(Optional.empty());

        when(applicationRepository.findById(20001L))
                .thenReturn(Optional.of(enabledApplication()));

        when(shortLinkRepository.trySave(any(ShortLink.class)))
                .thenAnswer(invocation -> {
                    ShortLink shortLink = invocation.getArgument(0);
                    shortLink.setId(30001L);
                    shortLink.setTenantId(10001L);
                    return true;
                });

        when(shortCodeGenerator.generate())
                .thenReturn("collision", "aZ8k2LmP");

        when(mappingRepository.trySave(any(ShortLinkMapping.class)))
                .thenReturn(false, true);

        CreatedShortLink result = shortLinkService.create(
                CreateShortLinkCommand.management(
                        20001L,
                        "req-001",
                        "https://example.com/orders/1",
                        expireAt
                )
        );

        assertEquals("aZ8k2LmP", result.shortCode());
        assertEquals(expireAt, result.expiredAt());

        ArgumentCaptor<ShortLinkMapping> captor =
                ArgumentCaptor.forClass(ShortLinkMapping.class);

        verify(mappingRepository, times(2))
                .trySave(captor.capture());

        List<String> attemptedCodes = captor
                .getAllValues()
                .stream()
                .map(ShortLinkMapping::getShortCode)
                .toList();

        assertEquals(
                List.of("collision", "aZ8k2LmP"),
                attemptedCodes
        );

        verify(eventPublisher).publishEvent(
                new ShortLinkCreatedEvent("aZ8k2LmP")
        );
    }

    @Test
    void shouldReturnExistingResultForIdempotentReplay() {
        LocalDateTime expireAt = LocalDateTime
                .now()
                .plusDays(7)
                .withNano(0);

        ShortLink existing = new ShortLink();
        existing.setId(30001L);
        existing.setTenantId(10001L);
        existing.setApplicationId(20001L);
        existing.setBusinessType(ShortLinkBusinessType.MANAGEMENT);
        existing.setIdempotencyKey("req-001");
        existing.setOriginalUrl("https://example.com/orders/1");
        existing.setExpireAt(expireAt);
        existing.setStatus(ShortLinkStatus.ACTIVE);

        ShortLinkMapping mapping = new ShortLinkMapping();
        mapping.setShortLinkId(30001L);
        mapping.setShortCode("aZ8k2LmP");

        when(shortLinkRepository.findByIdempotencyKey(
                20001L,
                ShortLinkBusinessType.MANAGEMENT,
                "req-001"
        )).thenReturn(Optional.of(existing));

        when(mappingRepository.findByShortLinkId(30001L))
                .thenReturn(Optional.of(mapping));

        CreatedShortLink result = shortLinkService.create(
                CreateShortLinkCommand.management(
                        20001L,
                        "req-001",
                        "https://example.com/orders/1",
                        expireAt
                )
        );

        assertEquals(30001L, result.id());
        assertEquals("aZ8k2LmP", result.shortCode());

        // 幂等重放不会创建新记录、生成新短码或重复发布事件。
        verify(shortLinkRepository, never()).trySave(any());
        verify(shortCodeGenerator, never()).generate();
        verify(eventPublisher, never()).publishEvent(any());
    }

    private Application enabledApplication() {
        Application application = new Application();
        application.setId(20001L);
        application.setTenantId(10001L);
        application.setStatus(1);
        return application;
    }
}
