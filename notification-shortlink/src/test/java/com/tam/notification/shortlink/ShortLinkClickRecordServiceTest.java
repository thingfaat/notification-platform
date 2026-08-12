package com.tam.notification.shortlink;

import com.tam.notification.domain.shortlink.ShortLinkClickEvent;
import com.tam.notification.domain.shortlink.ShortLinkClickEventPublisher;
import com.tam.notification.shortlink.dto.ResolvedShortLink;
import com.tam.notification.shortlink.service.ShortLinkClickRecordService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
public class ShortLinkClickRecordServiceTest {

    @Mock
    private ShortLinkClickEventPublisher publisher;

    @InjectMocks
    private ShortLinkClickRecordService service;

    @Test
    void shouldPublishClickEvent() {
        ResolvedShortLink resolved = new ResolvedShortLink(
                "aZ8k2LmP",
                10001L,
                30001L,
                "https://example.com/orders/1"
        );

        service.record(
                resolved,
                "visitor-key"
        );

        ArgumentCaptor<ShortLinkClickEvent> captor = ArgumentCaptor.forClass(
                ShortLinkClickEvent.class
        );

        verify(publisher).publish(
                captor.capture()
        );

        ShortLinkClickEvent event = captor.getValue();

        assertNotNull(event.eventId());
        assertNotNull(event.occurredAt());
        assertEquals(10001L, event.tenantId());
        assertEquals(30001L, event.shortLinkId());
        assertEquals("aZ8k2LmP", event.shortCode());
        assertEquals(
                "visitor-key",
                event.visitorKey()
        );
    }
}
