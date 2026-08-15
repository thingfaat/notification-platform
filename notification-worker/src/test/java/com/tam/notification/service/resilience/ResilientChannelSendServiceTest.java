package com.tam.notification.service.resilience;

import com.tam.notification.channel.ChannelSenderRouter;
import com.tam.notification.common.tenant.TenantContext;
import com.tam.notification.common.trace.TraceContext;
import com.tam.notification.domain.channel.ChannelSendCommand;
import com.tam.notification.domain.channel.ChannelSendResult;
import com.tam.notification.domain.channel.ChannelSendResultType;
import com.tam.notification.domain.channel.ChannelSender;
import com.tam.notification.domain.enums.ChannelType;
import com.tam.notification.resilience.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ResilientChannelSendServiceTest {

    private ChannelCallExecutor callExecutor;

    private SimpleMeterRegistry meterRegistry;

    @AfterEach
    void tearDown() {
        if (callExecutor != null) {
            callExecutor.close();
        }

        if (meterRegistry != null) {
            meterRegistry.close();
        }

        TenantContext.clear();
        TraceContext.clear();
    }

    @Test
    void retryablePrimaryFailureShouldSwitchToBackup() {
        StubSender primary = sender(
                "primary",
                100,
                ignored -> ChannelSendResult
                        .retryableFailure(
                                "PROVIDER_BUSY",
                                "busy"
                        )
        );

        StubSender backup = sender(
                "backup",
                200,
                ignored -> ChannelSendResult.success(
                        "backup-message-id"
                )
        );

        ChannelSendResult result = service(
                properties(3),
                primary,
                backup
        ).send(command());

        assertEquals(
                ChannelSendResultType.SUCCESS,
                result.type()
        );

        assertEquals(
                "backup-message-id",
                result.providerMessageId()
        );

        assertEquals(1, primary.calls());
        assertEquals(1, backup.calls());

        assertEquals(
                1.0,
                meterRegistry
                        .get("notification.channel.failover")
                        .tag("channel", "sms")
                        .tag("provider", "primary")
                        .tag("reason", "retryable_failure")
                        .counter()
                        .count()
        );
    }

    @Test
    void permanentFailureShouldNotSwitchToBackup() {
        StubSender primary = sender(
                "primary",
                100,
                ignored -> ChannelSendResult
                        .permanentFailure(
                                "INVALID_RECEIVER",
                                "invalid"
                        )
        );

        StubSender backup = sender(
                "backup",
                200,
                ignored -> ChannelSendResult.success(
                        "should-not-send"
                )
        );

        ChannelSendResult result = service(
                properties(3),
                primary,
                backup
        ).send(command());

        assertEquals(
                ChannelSendResultType.PERMANENT_FAILURE,
                result.type()
        );

        assertEquals(1, primary.calls());
        assertEquals(0, backup.calls());
    }

    @Test
    void timeoutShouldNotBlindlySwitchToBackup() {
        ChannelResilienceProperties properties =
                properties(3);

        properties.setCallTimeout(
                Duration.ofMillis(50)
        );

        StubSender primary = sender(
                "primary",
                100,
                ignored -> {
                    try {
                        Thread.sleep(5_000);
                    } catch (
                            InterruptedException exception
                    ) {
                        Thread.currentThread().interrupt();
                    }

                    return ChannelSendResult.success(
                            "unknown-result"
                    );
                }
        );

        StubSender backup = sender(
                "backup",
                200,
                ignored -> ChannelSendResult.success(
                        "should-not-send"
                )
        );

        ChannelResilienceException exception =
                assertThrows(
                        ChannelResilienceException.class,
                        () -> service(
                                properties,
                                primary,
                                backup
                        ).send(command())
                );

        assertEquals(
                ChannelResilienceException.Type.TIMEOUT,
                exception.getType()
        );

        assertEquals(1, primary.calls());
        assertEquals(0, backup.calls());

        assertEquals(
                1,
                meterRegistry
                        .get("notification.channel.call.duration")
                        .tag("channel", "sms")
                        .tag("provider", "primary")
                        .tag("outcome", "timeout")
                        .timer()
                        .count()
        );
    }

    @Test
    void circuitOpenedByDefinitiveFailuresShouldUseBackup() {
        ChannelResilienceProperties properties =
                properties(2);

        StubSender primary = sender(
                "primary",
                100,
                ignored -> ChannelSendResult
                        .retryableFailure(
                                "PROVIDER_BUSY",
                                "busy"
                        )
        );

        StubSender backup = sender(
                "backup",
                200,
                ignored -> ChannelSendResult.success(
                        "backup-message-id"
                )
        );

        ResilientChannelSendService service =
                service(
                        properties,
                        primary,
                        backup
                );

        service.send(command());
        service.send(command());

        ChannelSendResult third =
                service.send(command());

        assertEquals(
                ChannelSendResultType.SUCCESS,
                third.type()
        );

        /*
         * 前两次调用主供应商。
         * 第三次熔断，直接进入备用供应商。
         */
        assertEquals(2, primary.calls());
        assertEquals(3, backup.calls());

        assertEquals(
                1.0,
                meterRegistry
                        .get("notification.channel.circuit.rejected")
                        .tag("channel", "sms")
                        .tag("provider", "primary")
                        .tag("failover_allowed", "true")
                        .counter()
                        .count()
        );

        assertEquals(
                1.0,
                meterRegistry
                        .get("notification.channel.failover")
                        .tag("channel", "sms")
                        .tag("provider", "primary")
                        .tag("reason", "circuit_open")
                        .counter()
                        .count()
        );
    }

    @Test
    void circuitOpenedByUnknownFailureShouldNotFailover() {
        StubSender primary = sender(
                "primary",
                100,
                ignored -> {
                    throw new IllegalStateException(
                            "unknown result"
                    );
                }
        );

        StubSender backup = sender(
                "backup",
                200,
                ignored -> ChannelSendResult.success(
                        "should-not-send"
                )
        );

        ResilientChannelSendService service =
                service(
                        properties(1),
                        primary,
                        backup
                );

        assertThrows(
                IllegalStateException.class,
                () -> service.send(command())
        );

        ChannelResilienceException exception =
                assertThrows(
                        ChannelResilienceException.class,
                        () -> service.send(command())
                );

        assertEquals(
                ChannelResilienceException
                        .Type.CIRCUIT_OPEN,
                exception.getType()
        );

        assertEquals(1, primary.calls());
        assertEquals(0, backup.calls());

        assertEquals(
                1.0,
                meterRegistry
                        .get("notification.channel.circuit.rejected")
                        .tag("channel", "sms")
                        .tag("provider", "primary")
                        .tag("failover_allowed", "false")
                        .counter()
                        .count()
        );
    }

    @Test
    void shouldPropagateAndClearThreadContext() {
        TenantContext.setTenantId(10001L);
        TraceContext.setTraceId("trace-001");

        StubSender primary = sender(
                "primary",
                100,
                ignored -> {
                    assertEquals(
                            10001L,
                            TenantContext.requireTenantId()
                    );

                    assertEquals(
                            "trace-001",
                            TraceContext.getTraceId()
                    );

                    return ChannelSendResult.success(
                            "provider-message-id"
                    );
                }
        );

        ChannelSendResult result = service(
                properties(3),
                primary
        ).send(command());

        assertEquals(
                ChannelSendResultType.SUCCESS,
                result.type()
        );
    }

    private ResilientChannelSendService service(
            ChannelResilienceProperties properties,
            ChannelSender... senders
    ) {
        meterRegistry = new SimpleMeterRegistry();

        callExecutor = new ChannelCallExecutor(
                properties,
                meterRegistry
        );

        ChannelCircuitBreaker circuitBreaker =
                new ChannelCircuitBreaker(properties);

        return new ResilientChannelSendService(
                new ChannelSenderRouter(
                        List.of(senders)
                ),
                callExecutor,
                circuitBreaker,
                new ChannelMetrics(meterRegistry)
        );
    }

    private ChannelResilienceProperties properties(
            int failureThreshold
    ) {
        ChannelResilienceProperties properties =
                new ChannelResilienceProperties();

        properties.setCorePoolSize(1);
        properties.setMaxPoolSize(1);
        properties.setQueueCapacity(2);
        properties.setKeepAlive(
                Duration.ofSeconds(1)
        );
        properties.setCallTimeout(
                Duration.ofSeconds(1)
        );
        properties.setFailureThreshold(
                failureThreshold
        );
        properties.setOpenDuration(
                Duration.ofMinutes(1)
        );

        return properties;
    }

    private StubSender sender(
            String providerCode,
            int priority,
            Function<
                    ChannelSendCommand,
                    ChannelSendResult
                    > behavior
    ) {
        return new StubSender(
                providerCode,
                priority,
                behavior
        );
    }

    private ChannelSendCommand command() {
        return new ChannelSendCommand(
                1L,
                1,
                "MSG_001:1",
                ChannelType.SMS,
                "13800138000",
                "test"
        );
    }

    private static final class StubSender
            implements ChannelSender {

        private final String providerCode;
        private final int priority;

        private final Function<
                ChannelSendCommand,
                ChannelSendResult
                > behavior;

        private final AtomicInteger calls =
                new AtomicInteger();

        private StubSender(
                String providerCode,
                int priority,
                Function<
                        ChannelSendCommand,
                        ChannelSendResult
                        > behavior
        ) {
            this.providerCode = providerCode;
            this.priority = priority;
            this.behavior = behavior;
        }

        @Override
        public ChannelType channelType() {
            return ChannelType.SMS;
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
            calls.incrementAndGet();
            return behavior.apply(command);
        }

        private int calls() {
            return calls.get();
        }
    }
}
