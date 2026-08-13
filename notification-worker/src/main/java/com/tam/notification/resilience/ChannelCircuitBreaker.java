package com.tam.notification.resilience;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 渠道熔断器
 */
@Component
public class ChannelCircuitBreaker {

    /**
     * 熔断器状态
     */
    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    /**
     * 熔断器许可
     *
     * @param allowed
     * @param failoverAllowed
     */
    public record Permission(
            boolean allowed,
            boolean failoverAllowed
    ) {
    }

    // 熔断器状态
    private final ConcurrentMap<String, CircuitState> states = new ConcurrentHashMap<>();

    // 熔断失败阈值
    private final int failureThreshold;

    // 熔断打开时间
    private final long openDurationNanos;

    public ChannelCircuitBreaker(
            ChannelResilienceProperties properties
    ) {
        if (properties.getFailureThreshold() <= 0) {
            throw new IllegalStateException("熔断失败阈值必须大于0");
        }

        Duration openDuration = properties.getOpenDuration();
        if (openDuration == null
                || openDuration.isZero()
                || openDuration.isNegative()) {
            throw new IllegalStateException(
                    "熔断打开时间必须大于0"
            );
        }

        this.failureThreshold = properties.getFailureThreshold();
        this.openDurationNanos = openDuration.toNanos();
    }

    /**
     * 尝试获取许可
     *
     * @param providerCode
     * @return
     */
    public Permission tryAcquire(
            String providerCode
    ) {
        return stateOf(providerCode).tryAcquire(System.nanoTime());
    }

    public void recordSuccess(
            String providerCode
    ) {
        stateOf(providerCode).recordSuccess();
    }

    /**
     * 供应商明确表示没有受理请求，这种失败打开熔断后可以安全切换备用
     *
     * @param providerCode
     */
    public void recordDefinitiveFailure(
            String providerCode
    ) {
        stateOf(providerCode).recordFailure(
                System.nanoTime(),
                failureThreshold,
                openDurationNanos,
                true
        );
    }

    /**
     * 超时、连接断开等结果未知的失败，打开熔断器后不能自动切换备用
     *
     * @param providerCode
     */
    public void recordUnknownFailure(
            String providerCode
    ) {
        stateOf(providerCode).recordFailure(
                System.nanoTime(),
                failureThreshold,
                openDurationNanos,
                false
        );
    }

    public void recordNeutral(
            String providerCode
    ) {
        stateOf(providerCode).recordNeutral(
                System.nanoTime(),
                openDurationNanos
        );
    }

    public State currentState(
            String providerCode
    ) {
        return stateOf(providerCode).currentState(System.nanoTime());
    }

    /**
     * 获取熔断器状态
     *
     * @param providerCode
     * @return
     */
    private CircuitState stateOf(
            String providerCode
    ) {
        // 容器中如果没有的话就创建一个，如果已经有了就返回已经存在的熔断器
        return states.computeIfAbsent(
                providerCode,
                ignored -> new CircuitState()
        );
    }

    /**
     * 熔断器状态
     */
    private static final class CircuitState {

        // 连续失败次数
        private int consecutiveFailures;

        // 熔断打开时间
        private long openUntilNanos;

        // 半开探测请求是否在请求过程中
        private boolean halfOpenProbeInFlight;

        /*
         * 熔断器是否只由“明确未受理”打开。
         * 只要出现过结果未知失败，就保守禁止切备用。
         */
        private boolean failoverAllowed = true;

        /**
         * 尝试获取许可
         *
         * @param nowNanos
         * @return
         */
        synchronized Permission tryAcquire(
                long nowNanos
        ) {
            // 熔断器处于CLOSED状态
            if (openUntilNanos == 0) {
                return new Permission(true, true);
            }

            // 在openUntilNanos之前，熔断器处于OPEN状态，所以送入的当前时间如果小于openUntilNanos，则返回false
            if (nowNanos < openUntilNanos) {
                return new Permission(
                        false,
                        failoverAllowed
                );
            }

            /*
             * OPEN时间结束，只允许一个探测请求。
             */
            if (halfOpenProbeInFlight) {
                return new Permission(
                        false,
                        failoverAllowed
                );
            }

            halfOpenProbeInFlight = true;

            // 在open状态下，熔断器处于HALF_OPEN状态，允许1个探测请求
            return new Permission(
                    true,
                    failoverAllowed
            );
        }

        /**
         * 记录成功
         */
        synchronized void recordSuccess() {
            consecutiveFailures = 0; // 重置连续失败次数
            openUntilNanos = 0; // 关闭熔断器
            halfOpenProbeInFlight = false; // 停止探测请求
            failoverAllowed = true; // 允许切换备用
        }

        /**
         * 记录失败
         *
         * @param nowNanos
         * @param threshold
         * @param durationNanos
         * @param definitive
         */
        synchronized void recordFailure(
                long nowNanos,
                int threshold,
                long durationNanos,
                boolean definitive
        ) {
            consecutiveFailures++; // 连续失败次数+1

            // 明确未受理的失败打开熔断器后，不允许切换备用
            if (!definitive) {
                failoverAllowed = false;
            }

            // 熔断器处于HALF_OPEN状态，或者连续失败次数达到阈值，则打开熔断器
            if (halfOpenProbeInFlight
                    || consecutiveFailures >= threshold) {
                openUntilNanos = nowNanos + durationNanos;
                halfOpenProbeInFlight = false;
            }
        }

        /**
         * 记录中立结果
         *
         * @param nowNanos
         * @param durationNanos
         */
        synchronized void recordNeutral(
                long nowNanos,
                long durationNanos
        ) {
            // 熔断器处于HALF_OPEN状态，则打开熔断器
            if (halfOpenProbeInFlight) {
                halfOpenProbeInFlight = false;
                openUntilNanos = nowNanos + durationNanos; // 打开时间延续到 durationNanos
            }
        }

        /**
         * 获取熔断器状态
         *
         * @param nowNanos
         * @return
         */
        synchronized State currentState(
                long nowNanos
        ) {
            // 如果打开延续时间是0，熔断器处于CLOSED状态
            if (openUntilNanos == 0) {
                return State.CLOSED;
            }

            // 如果打开延续时间大于0，并且当前时间小于打开延续时间，熔断器处于OPEN状态
            if (nowNanos < openUntilNanos) {
                return State.OPEN;
            }

            // 熔断器处于HALF_OPEN状态
            return State.HALF_OPEN;
        }
    }
}
