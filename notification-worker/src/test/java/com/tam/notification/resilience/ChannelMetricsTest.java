package com.tam.notification.resilience;

import com.tam.notification.domain.channel.ChannelSendCommand;
import com.tam.notification.domain.channel.ChannelSendResult;
import com.tam.notification.domain.channel.ChannelSender;
import com.tam.notification.domain.enums.ChannelType;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChannelMetricsTest {

    @Test
    void shouldRecordCallFailoverAndCircuitState() {
        SimpleMeterRegistry registry =
                new SimpleMeterRegistry();

        try {
            ChannelMetrics metrics =
                    new ChannelMetrics(registry);

            ChannelSender sender =
                    new StubSender();

            Timer.Sample sample =
                    metrics.startCall();

            metrics.recordCall(
                    sample,
                    sender,
                    "SUCCESS"
            );

            metrics.recordFailover(
                    sender,
                    "RETRYABLE_FAILURE"
            );

            metrics.recordCircuitRejected(
                    sender,
                    true
            );

            metrics.updateCircuitState(
                    sender,
                    ChannelCircuitBreaker.State.OPEN
            );

            assertEquals(
                    1,
                    registry
                            .get(
                                    "notification.channel"
                                            + ".call.duration"
                            )
                            .tag("channel", "sms")
                            .tag(
                                    "provider",
                                    "mock-sms-primary"
                            )
                            .tag("outcome", "success")
                            .timer()
                            .count()
            );

            assertEquals(
                    1.0,
                    registry
                            .get(
                                    "notification.channel"
                                            + ".failover"
                            )
                            .tag("channel", "sms")
                            .tag(
                                    "provider",
                                    "mock-sms-primary"
                            )
                            .tag(
                                    "reason",
                                    "retryable_failure"
                            )
                            .counter()
                            .count()
            );

            assertEquals(
                    1.0,
                    registry
                            .get(
                                    "notification.channel"
                                            + ".circuit.rejected"
                            )
                            .tag("channel", "sms")
                            .tag(
                                    "provider",
                                    "mock-sms-primary"
                            )
                            .tag(
                                    "failover_allowed",
                                    "true"
                            )
                            .counter()
                            .count()
            );

            assertEquals(
                    1.0,
                    registry
                            .get(
                                    "notification.channel"
                                            + ".circuit.state"
                            )
                            .tag("channel", "sms")
                            .tag(
                                    "provider",
                                    "mock-sms-primary"
                            )
                            .tag("state", "open")
                            .gauge()
                            .value()
            );

            assertEquals(
                    0.0,
                    registry
                            .get(
                                    "notification.channel"
                                            + ".circuit.state"
                            )
                            .tag("channel", "sms")
                            .tag(
                                    "provider",
                                    "mock-sms-primary"
                            )
                            .tag("state", "closed")
                            .gauge()
                            .value()
            );
        } finally {
            registry.close();
        }
    }

    private static final class StubSender
            implements ChannelSender {

        @Override
        public ChannelType channelType() {
            return ChannelType.SMS;
        }

        @Override
        public String providerCode() {
            return "mock-sms-primary";
        }

        @Override
        public int priority() {
            return 100;
        }

        @Override
        public ChannelSendResult send(
                ChannelSendCommand command
        ) {
            return ChannelSendResult.success(
                    "provider-message-id"
            );
        }
    }
}
