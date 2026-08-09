package com.tam.notification.channel;

import com.tam.notification.domain.channel.ChannelSendCommand;
import com.tam.notification.domain.channel.ChannelSendResult;
import com.tam.notification.domain.channel.ChannelSendResultType;
import com.tam.notification.domain.channel.ChannelSender;
import com.tam.notification.domain.enums.ChannelType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ChannelSenderReliabilityTest {

    private final ChannelSender sender = new MockSmsChannelSender();

    @Test
    void sameIdempotencyKeyShouldReturnSameResult() {
        ChannelSendCommand command = command(
                1,
                "MSG_001:1",
                "13800138000"
        );
        ChannelSendResult first = sender.send(command);
        ChannelSendResult second = sender.send(command);

        assertEquals(ChannelSendResultType.SUCCESS, first.type());
        assertEquals(first, second);
        assertEquals(
                first.providerMessageId(),
                second.providerMessageId()
        );
    }

    @Test
    void exceptionOnceShouldRecoverWithSameAttempt() {
        ChannelSendCommand command = command(
                1,
                "MSG_002:1",
                "exception-once:test"
        );

        assertThrows(
                IllegalStateException.class,
                () -> sender.send(command)
        );

        ChannelSendResult result = sender.send(command);

        assertEquals(
                ChannelSendResultType.SUCCESS,
                result.type()
        );
    }

    @Test
    void retryableFailureShouldUseNewAttempt() {
        ChannelSendResult first = sender.send(
                command(1, "MSG_003:1", "retry:test")
        );

        ChannelSendResult second = sender.send(
                command(2, "MSG_003:2", "retry:test")
        );

        ChannelSendResult third = sender.send(
                command(3, "MSG_003:3", "retry:test")
        );

        assertEquals(
                ChannelSendResultType.RETRYABLE_FAILURE,
                first.type()
        );
        assertEquals(
                ChannelSendResultType.RETRYABLE_FAILURE,
                second.type()
        );
        assertEquals(
                ChannelSendResultType.SUCCESS,
                third.type()
        );
    }

    @Test
    void exceptionAlwaysShouldKeepThrowing() {
        ChannelSendCommand command = command(
                1,
                "MSG_004:1",
                "exception-always:test"
        );

        assertThrows(
                IllegalStateException.class,
                () -> sender.send(command)
        );

        assertThrows(
                IllegalStateException.class,
                () -> sender.send(command)
        );
    }

    private ChannelSendCommand command(
            int attemptNo,
            String idempotencyKey,
            String receiver
    ) {
        return new ChannelSendCommand(
                1L,
                attemptNo,
                idempotencyKey,
                ChannelType.SMS,
                receiver,
                "测试内容"
        );
    }
}
