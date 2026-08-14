package com.tam.notification.channel;

import com.tam.notification.domain.channel.ChannelSendCommand;
import com.tam.notification.domain.channel.ChannelSendResult;
import com.tam.notification.domain.channel.ChannelSender;
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

    @Test
    void shouldRejectDuplicatedPriorityInSameChannel() {
        ChannelSender samePriority = sender(
                ChannelType.SMS,
                "another-sms-provider",
                100
        );

        assertThrows(
                IllegalStateException.class,
                () -> new ChannelSenderRouter(
                        List.of(
                                new MockSmsChannelSender(),
                                samePriority
                        )
                )
        );
    }

    @Test
    void shouldAllowSameProviderCodeInDifferentChannels() {
        ChannelSender sms = sender(
                ChannelType.SMS,
                "shared-provider",
                100
        );

        ChannelSender email = sender(
                ChannelType.EMAIL,
                "shared-provider",
                100
        );

        ChannelSenderRouter router = new ChannelSenderRouter(
                List.of(sms, email)
        );

        assertSame(sms, router.route(ChannelType.SMS));
        assertSame(email, router.route(ChannelType.EMAIL));
    }

    @Test
    void shouldReturnImmutableCandidateList() {
        ChannelSenderRouter router = new ChannelSenderRouter(
                List.of(
                        new MockSmsChannelSender(),
                        new BackupMockSmsChannelSender()
                )
        );

        List<ChannelSender> candidates = router.routeCandidates(
                ChannelType.SMS
        );

        assertThrows(
                UnsupportedOperationException.class,
                candidates::clear
        );
    }

    private ChannelSender sender(
            ChannelType channelType,
            String providerCode,
            int priority
    ) {
        return new ChannelSender() {
            @Override
            public ChannelType channelType() {
                return channelType;
            }

            @Override
            public String providerCode() {
                return providerCode;
            }

            @Override
            public int priority() {
                return priority;
            }

            @Override
            public ChannelSendResult send(
                    ChannelSendCommand command
            ) {
                return ChannelSendResult.success(
                        "provider-message-id"
                );
            }
        };
    }
}
