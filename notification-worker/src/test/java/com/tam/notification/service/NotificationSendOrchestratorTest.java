package com.tam.notification.service;

import com.tam.notification.domain.outbox.NotificationSendEvent;
import com.tam.notification.resilience.ResilientChannelSendService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class NotificationSendOrchestratorTest {

    @Mock
    private MessageSendTransactionService transactionService;

    @Mock
    private ResilientChannelSendService channelSendService;

    @Mock
    private NotificationRateLimitService rateLimitService;

    @InjectMocks
    private NotificationSendOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(
                orchestrator,
                "consumerGroup",
                "notification-worker-group"
        );
    }

    @Test
    void shouldNotCallChannelWhenCompletedEventIsDeliveredAgain() {
        NotificationSendEvent event = new NotificationSendEvent(
                "completed-event",
                1001L,
                2001L,
                3001L,
                4001L,
                "MSG_COMPLETED",
                "NOTIFICATION_SEND",
                "trace-completed",
                null
        );

        when(rateLimitService.allowOrDefer(event)).thenReturn(true);
        // Optional.empty 代表 consume_record 已经存在。
        when(transactionService.prepare(
                event,
                "notification-worker-group"
        )).thenReturn(Optional.empty());

        orchestrator.send(event);

        verifyNoInteractions(channelSendService);
        verify(transactionService, never()).finishSuccess(
                any(),
                anyString(),
                any(),
                any()
        );
        verify(transactionService, never()).finishFailure(
                any(),
                anyString(),
                any(),
                any()
        );
    }
}
