package com.tam.notification.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tam.notification.common.tenant.TenantContext;
import com.tam.notification.common.trace.TraceContext;
import com.tam.notification.domain.outbox.NotificationSendEvent;
import com.tam.notification.observability.MqConsumeMetrics;
import com.tam.notification.service.NotificationSendOrchestrator;
import io.micrometer.core.instrument.Timer;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

public class NotificationSendListenerTest {

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        TraceContext.clear();
    }

    @Test
    void shouldDeserializeMessageExtAndClearThreadContext() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        NotificationSendOrchestrator orchestrator = mock(
                NotificationSendOrchestrator.class
        );
        WorkerIdentity workerIdentity = mock(WorkerIdentity.class);
        when(workerIdentity.instanceId()).thenReturn("worker-a");
        MqConsumeMetrics metrics = mock(MqConsumeMetrics.class);
        Timer.Sample sample = mock(Timer.Sample.class);
        when(metrics.start(2)).thenReturn(sample);

        NotificationSendListener listener = new NotificationSendListener(
                objectMapper,
                orchestrator,
                workerIdentity,
                metrics
        );

        NotificationSendEvent event = new NotificationSendEvent(
                "event-20",
                1001L,
                2001L,
                3001L,
                4001L,
                "MSG_DAY20",
                "NOTIFICATION_SEND",
                "trace-day20",
                null
        );

        MessageExt message = new MessageExt();
        message.setBody(
                objectMapper.writeValueAsString(event)
                        .getBytes(StandardCharsets.UTF_8)
        );
        message.setMsgId("mq-msg-20");
        message.setQueueId(1);
        message.setQueueOffset(20L);
        message.setReconsumeTimes(2);

        // 在 Orchestrator 执行时，上下文必须已经恢复。
        doAnswer(invocation -> {
            assertEquals(1001L, TenantContext.requireTenantId());
            assertEquals("trace-day20", TraceContext.getTraceId());
            return null;
        }).when(orchestrator).send(event);

        listener.onMessage(message);

        verify(orchestrator).send(event);
        verify(metrics).recordSuccess(sample);
        verify(metrics, never()).recordFailure(sample);
        // 消费线程会复用，所以执行结束后必须清空。
        assertNull(TenantContext.getTenantId());
        assertNull(TraceContext.getTraceId());
    }

    @Test
    void shouldRecordFailureAndRethrowInvalidMessage() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        NotificationSendOrchestrator orchestrator = mock(
                NotificationSendOrchestrator.class
        );
        WorkerIdentity workerIdentity = mock(WorkerIdentity.class);
        MqConsumeMetrics metrics = mock(MqConsumeMetrics.class);
        Timer.Sample sample = mock(Timer.Sample.class);
        when(metrics.start(0)).thenReturn(sample);

        NotificationSendListener listener = new NotificationSendListener(
                objectMapper,
                orchestrator,
                workerIdentity,
                metrics
        );

        MessageExt message = new MessageExt();
        message.setBody("invalid-json".getBytes(StandardCharsets.UTF_8));
        message.setReconsumeTimes(0);

        assertThrows(
                IllegalArgumentException.class,
                () -> listener.onMessage(message)
        );

        verify(metrics).recordFailure(sample);
        verify(metrics, never()).recordSuccess(sample);
        verifyNoInteractions(orchestrator);
    }

    @Test
    void shouldConfigureConsumerBeforeStart() {
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        NotificationSendOrchestrator orchestrator = mock(
                NotificationSendOrchestrator.class
        );
        WorkerIdentity workerIdentity = mock(WorkerIdentity.class);
        DefaultMQPushConsumer consumer = mock(DefaultMQPushConsumer.class);
        MqConsumeMetrics metrics = mock(MqConsumeMetrics.class);

        NotificationSendListener listener = new NotificationSendListener(
                objectMapper,
                orchestrator,
                workerIdentity,
                metrics
        );

        listener.prepareStart(consumer);

        verify(workerIdentity).configure(
                consumer,
                "notification-send"
        );
    }
}
