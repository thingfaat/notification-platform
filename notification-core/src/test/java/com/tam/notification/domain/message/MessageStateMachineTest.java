package com.tam.notification.domain.message;

import com.tam.notification.domain.enums.MessageStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class MessageStateMachineTest {
    @Test
    void shouldAllowCreateToQueued() {
        assertDoesNotThrow(() -> MessageStateMachine.checkTransition(MessageStatus.CREATED, MessageStatus.QUEUED));
    }

    @Test
    void shouldRejectCreatedToDelivered() {
        assertThrows(IllegalStateException.class, () -> MessageStateMachine.checkTransition(MessageStatus.CREATED, MessageStatus.DELIVERED));
    }

    @Test
    void shouldAllowDeadToQueuedForManualRetry() {
        assertDoesNotThrow(() -> MessageStateMachine.checkTransition(MessageStatus.DEAD, MessageStatus.QUEUED));
    }
}
