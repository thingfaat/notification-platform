package com.tam.notification.shortlink;

import com.tam.notification.domain.application.Application;
import com.tam.notification.domain.application.ApplicationRepository;
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
        Application application = new Application();
        application.setId(20001L);
        application.setTenantId(10001L);
        application.setStatus(1);

        when(applicationRepository.findById(20001L))
                .thenReturn(Optional.of(application));

        when(shortLinkRepository.save(any(ShortLink.class)))
                .thenAnswer(invocation -> {
                    ShortLink shortLink = invocation.getArgument(0);
                    shortLink.setId(30001L);
                    shortLink.setTenantId(10001L);
                    return shortLink;
                });

        when(shortCodeGenerator.generate())
                .thenReturn("collision", "aZ8k2LmP");

        when(mappingRepository.trySave(any(ShortLinkMapping.class)))
                .thenReturn(false, true);

        CreatedShortLink result = shortLinkService.create(
                new CreateShortLinkCommand(
                        20001L,
                        "https://example.com/orders/1",
                        LocalDateTime.now().plusDays(7)
                )
        );

        assertEquals("aZ8k2LmP", result.shortCode());

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
}
