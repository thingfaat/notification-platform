package com.tam.notification.channel;

import com.tam.notification.domain.enums.ChannelType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ChannelSenderRouterTest {

    @Test
    void shouldRouteToMatchingSender() {
        MockSmsChannelSender smsChannelSender = new MockSmsChannelSender();
        MockEmailChannelSender emailChannelSender = new MockEmailChannelSender();
        InAppChannelSender inAppChannelSender = new InAppChannelSender();

        ChannelSenderRouter router = new ChannelSenderRouter(List.of(smsChannelSender, emailChannelSender, inAppChannelSender));

        assertSame(smsChannelSender, router.route(ChannelType.SMS));
        assertSame(emailChannelSender, router.route(ChannelType.EMAIL));
        assertSame(inAppChannelSender, router.route(ChannelType.IN_APP));
    }

    @Test
    void shouldRejectDuplicateSender() {
        assertThrows(IllegalStateException.class, () ->
                new ChannelSenderRouter(List.of(
                        new MockSmsChannelSender(),
                        new MockSmsChannelSender()
                ))
        );
    }
}
