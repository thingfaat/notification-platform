package com.tam.notification.resilience;

import com.tam.notification.domain.channel.ChannelSender;
import com.tam.notification.domain.enums.ChannelType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 渠道弹性指标
 * 指标标签只能使用渠道、供应商、结果等低基数字段，禁止加入messageId、requestId、receiver和traceId
 */
@Component
public class ChannelMetrics {

    // 渠道供应商调用耗时
    private static final String CALL_DURATION = "notification.channel.call.duration";

    // 尝试切换备用供应商的次数
    private static final String FAILOVER = "notification.channel.failover";

    // 被渠道熔断器拒绝的请求数
    private static final String CIRCUIT_REJECTED = "notification.channel.circuit.rejected";

    private static final String CIRCUIT_STATE = "notification.channel.circuit.state";

    private final MeterRegistry meterRegistry;

    private final ConcurrentMap<CallMetricKey, Timer> callTimers = new ConcurrentHashMap<>();

    private final ConcurrentMap<FailoverMetricKey, Counter> failoverCounters = new ConcurrentHashMap<>();

    private final ConcurrentMap<CircuitRejectedMetricKey, Counter> circuitRejectedCounters = new ConcurrentHashMap<>();

    private final ConcurrentMap<ProviderKey, Map<ChannelCircuitBreaker.State, AtomicInteger>> circuitStateGauges = new ConcurrentHashMap<>();

    public ChannelMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public Timer.Sample startCall() {
        return Timer.start(meterRegistry);
    }

    public void recordCall(
            Timer.Sample sample,
            ChannelSender sender,
            String outcome
    ) {
        CallMetricKey key = new CallMetricKey(
                sender.channelType(),
                sender.providerCode(),
                normalize(outcome)
        );

        Timer timer = callTimers.computeIfAbsent(
                key,
                this::createCallTimer
        );

        sample.stop(timer);
    }

    /**
     * 记录系统决定继续尝试备用供应商
     *
     * @param sender
     * @param reason
     */
    public void recordFailover(
            ChannelSender sender,
            String reason
    ) {
        FailoverMetricKey key = new FailoverMetricKey(
                sender.channelType(),
                sender.providerCode(),
                normalize(reason)
        );

        failoverCounters.computeIfAbsent(
                key,
                this::createFailoverCounter
        ).increment();
    }

    /**
     * 记录请求在熔断器处被拒绝，没有进入渠道线程池
     *
     * @param sender
     * @param failoverAllowed
     */
    public void recordCircuitRejected(
            ChannelSender sender,
            boolean failoverAllowed
    ) {
        CircuitRejectedMetricKey key = new CircuitRejectedMetricKey(
                sender.channelType(),
                sender.providerCode(),
                failoverAllowed
        );

        circuitRejectedCounters.computeIfAbsent(
                key,
                this::createCircuitRejectedCounter
        ).increment();
    }

    /**
     * 使用三个one-hot Gauge表示状态：
     * <p>
     * state=closed    1/0
     * state=open      1/0
     * state=half_open 1/0
     */
    public void updateCircuitState(
            ChannelSender sender,
            ChannelCircuitBreaker.State currentState
    ) {
        ProviderKey key = new ProviderKey(
                sender.channelType(),
                sender.providerCode()
        );

        Map<ChannelCircuitBreaker.State, AtomicInteger> gauges =
                circuitStateGauges.computeIfAbsent(
                        key,
                        this::createCircuitStateGauges
                );

        gauges.forEach((state, value) ->
                value.set(state == currentState ? 1 : 0)
        );
    }

    private Timer createCallTimer(
            CallMetricKey key
    ) {
        return Timer.builder(CALL_DURATION)
                .description("渠道供应商调用耗时")
                .tag("channel", channelTag(key.channelType()))
                .tag("provider", key.providerCode())
                .tag("outcome", key.outcome())
                .register(meterRegistry);
    }

    private Counter createFailoverCounter(
            FailoverMetricKey key
    ) {
        return Counter.builder(FAILOVER)
                .description("尝试切换备用供应商的次数")
                .tag("channel", channelTag(key.channelType()))
                .tag("provider", key.providerCode())
                .tag("reason", key.reason())
                .register(meterRegistry);
    }

    private Counter createCircuitRejectedCounter(
            CircuitRejectedMetricKey key
    ) {
        return Counter.builder(CIRCUIT_REJECTED)
                .description("被渠道熔断器拒绝的请求数")
                .tag("channel", channelTag(key.channelType()))
                .tag("provider", key.providerCode())
                .tag(
                        "failover_allowed",
                        Boolean.toString(key.failoverAllowed())
                )
                .register(meterRegistry);
    }

    private Map<ChannelCircuitBreaker.State, AtomicInteger>
    createCircuitStateGauges(
            ProviderKey key
    ) {
        EnumMap<ChannelCircuitBreaker.State, AtomicInteger> gauges =
                new EnumMap<>(ChannelCircuitBreaker.State.class);

        for (ChannelCircuitBreaker.State state
                : ChannelCircuitBreaker.State.values()) {

            AtomicInteger value = new AtomicInteger(
                    state == ChannelCircuitBreaker.State.CLOSED
                            ? 1
                            : 0
            );

            Gauge.builder(
                            CIRCUIT_STATE,
                            value,
                            AtomicInteger::get
                    )
                    .description("渠道熔断器当前状态，1表示当前处于该状态")
                    .tag("channel", channelTag(key.channelType()))
                    .tag("provider", key.providerCode())
                    .tag("state", normalize(state.name()))
                    .register(meterRegistry);

            gauges.put(state, value);
        }

        return Map.copyOf(gauges);
    }

    private String channelTag(ChannelType channelType) {
        return normalize(channelType.name());
    }

    private String normalize(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private record ProviderKey(
            ChannelType channelType,
            String providerCode
    ) {
    }

    private record CallMetricKey(
            ChannelType channelType,
            String providerCode,
            String outcome
    ) {
    }

    private record FailoverMetricKey(
            ChannelType channelType,
            String providerCode,
            String reason
    ) {
    }

    private record CircuitRejectedMetricKey(
            ChannelType channelType,
            String providerCode,
            boolean failoverAllowed
    ) {
    }
}
