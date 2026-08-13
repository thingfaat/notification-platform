package com.tam.notification.channel;

import com.tam.notification.domain.enums.ChannelType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChannelSenderRouterTest {

    @Test
    void shouldRouteCandidatesByPriority() {
        MockSmsChannelSender primary = new MockSmsChannelSender();

        BackupMockSmsChannelSender backup = new BackupMockSmsChannelSender();

        MockEmailChannelSender email = new MockEmailChannelSender();

        InAppChannelSender inApp = new InAppChannelSender();

        ChannelSenderRouter router = new ChannelSenderRouter(
                List.of(
                        backup,
                        primary,
                        email,
                        inApp
                )
        );

        assertSame(
                primary,
                router.route(ChannelType.SMS)
        );

        assertEquals(
                List.of(primary, backup),
                router.routeCandidates(
                        ChannelType.SMS
                )
        );

        assertSame(
                email,
                router.route(ChannelType.EMAIL)
        );

        assertSame(
                inApp,
                router.route(ChannelType.IN_APP)
        );
    }

    @Test
    void shouldRejectDuplicatedProviderRegistration() {
        assertThrows(
                IllegalStateException.class,
                () -> new ChannelSenderRouter(
                        List.of(
                                new MockSmsChannelSender(),
                                new MockSmsChannelSender()
                        )
                )
        );
    }
}
