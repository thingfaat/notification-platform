package com.tam.notification.resilience;

import com.tam.notification.domain.enums.ChannelType;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 渠道熔断器。
 *
 * <p>每个 {@link CircuitKey} 对应一个独立状态机。Permission 中的 generation
 * 用于识别旧状态周期中仍在执行的请求，避免旧请求结果破坏新的熔断状态。</p>
 */
@Component
public class ChannelCircuitBreaker {

    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    /**
     * 渠道类型和供应商共同确定一个熔断器，避免不同渠道相互污染。
     */
    public record CircuitKey(
            ChannelType channelType,
            String providerCode
    ) {
        public CircuitKey {
            Objects.requireNonNull(channelType, "channelType不能为空");
            if (providerCode == null || providerCode.isBlank()) {
                throw new IllegalArgumentException("providerCode不能为空");
            }
        }
    }

    /**
     * 一次渠道调用取得的熔断许可。
     *
     * @param allowed 是否允许执行渠道调用
     * @param failoverAllowed 熔断拒绝时是否允许切换备用供应商
     * @param generation 取得许可时的熔断器状态版本
     * @param halfOpenProbe 是否为 HALF_OPEN 探测请求
     */
    public record Permission(
            boolean allowed,
            boolean failoverAllowed,
            long generation,
            boolean halfOpenProbe
    ) {
    }

    private final ConcurrentMap<CircuitKey, CircuitState> states = new ConcurrentHashMap<>();

    private final int failureThreshold;

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
            throw new IllegalStateException("熔断打开时间必须大于0");
        }

        this.failureThreshold = properties.getFailureThreshold();
        this.openDurationNanos = openDuration.toNanos();
    }

    public Permission tryAcquire(
            CircuitKey key
    ) {
        return stateOf(key).tryAcquire(System.nanoTime());
    }

    public void recordSuccess(
            CircuitKey key,
            Permission permission
    ) {
        stateOf(key).recordSuccess(permission);
    }

    /**
     * 供应商明确表示没有受理请求，这种失败打开熔断器后可以安全切换备用。
     */
    public void recordDefinitiveFailure(
            CircuitKey key,
            Permission permission
    ) {
        stateOf(key).recordFailure(
                permission,
                System.nanoTime(),
                failureThreshold,
                openDurationNanos,
                true
        );
    }

    /**
     * 超时、连接断开等结果未知的失败，打开熔断器后不能自动切换备用。
     */
    public void recordUnknownFailure(
            CircuitKey key,
            Permission permission
    ) {
        stateOf(key).recordFailure(
                permission,
                System.nanoTime(),
                failureThreshold,
                openDurationNanos,
                false
        );
    }

    /**
     * 隔离拒绝没有实际调用供应商，不计为供应商失败。
     * 如果拒绝发生在 HALF_OPEN 探测阶段，则重新打开熔断器等待下一次探测。
     */
    public void recordNeutral(
            CircuitKey key,
            Permission permission
    ) {
        stateOf(key).recordNeutral(
                permission,
                System.nanoTime(),
                openDurationNanos
        );
    }

    public State currentState(
            CircuitKey key
    ) {
        return stateOf(key).currentState(System.nanoTime());
    }

    private CircuitState stateOf(
            CircuitKey key
    ) {
        Objects.requireNonNull(key, "circuitKey不能为空");
        return states.computeIfAbsent(
                key,
                ignored -> new CircuitState()
        );
    }

    private static final class CircuitState {

        private State state = State.CLOSED;

        /**
         * 只有状态周期切换时才递增，同一 CLOSED 周期内的并发请求共享同一代次。
         */
        private long generation;

        private int consecutiveFailures;

        private long openUntilNanos;

        private boolean halfOpenProbeInFlight;

        /**
         * 只要当前失败周期出现过结果未知失败，就保守禁止切换备用供应商。
         */
        private boolean failoverAllowed = true;

        synchronized Permission tryAcquire(
                long nowNanos
        ) {
            if (state == State.CLOSED) {
                return allowedPermission(false);
            }

            if (state == State.OPEN) {
                if (nowNanos < openUntilNanos) {
                    return deniedPermission();
                }

                state = State.HALF_OPEN;
                halfOpenProbeInFlight = true;
                return allowedPermission(true);
            }

            // HALF_OPEN 阶段只允许一个探测请求，其余请求继续被熔断。
            if (halfOpenProbeInFlight) {
                return deniedPermission();
            }

            // 防御性分支：正常状态转换不会出现 HALF_OPEN 但没有探测请求的情况。
            halfOpenProbeInFlight = true;
            return allowedPermission(true);
        }

        synchronized void recordSuccess(
                Permission permission
        ) {
            if (!isCurrent(permission)) {
                return;
            }

            if (state == State.CLOSED
                    && !permission.halfOpenProbe()) {
                consecutiveFailures = 0;
                failoverAllowed = true;
                return;
            }

            if (state == State.HALF_OPEN
                    && permission.halfOpenProbe()
                    && halfOpenProbeInFlight) {
                closeCircuit();
            }
        }

        synchronized void recordFailure(
                Permission permission,
                long nowNanos,
                int threshold,
                long durationNanos,
                boolean definitive
        ) {
            if (!isCurrent(permission)) {
                /*
                 * 旧代次结果不能改变当前计数或状态，但“结果未知”是安全例外：
                 * 如果当前仍处于 OPEN/HALF_OPEN，该旧请求可能已经发送成功，
                 * 它被 MQ 重投后不能直接切备用供应商。
                 */
                if (!definitive
                        && isOlderPermission(permission)
                        && state != State.CLOSED) {
                    failoverAllowed = false;
                }
                return;
            }

            if (!definitive) {
                failoverAllowed = false;
            }

            if (state == State.CLOSED
                    && !permission.halfOpenProbe()) {
                consecutiveFailures++;

                if (consecutiveFailures >= threshold) {
                    openCircuit(nowNanos, durationNanos);
                }
                return;
            }

            if (state == State.HALF_OPEN
                    && permission.halfOpenProbe()
                    && halfOpenProbeInFlight) {
                openCircuit(nowNanos, durationNanos);
            }
        }

        synchronized void recordNeutral(
                Permission permission,
                long nowNanos,
                long durationNanos
        ) {
            if (!isCurrent(permission)) {
                return;
            }

            if (state == State.HALF_OPEN
                    && permission.halfOpenProbe()
                    && halfOpenProbeInFlight) {
                openCircuit(nowNanos, durationNanos);
            }
        }

        synchronized State currentState(
                long nowNanos
        ) {
            if (state == State.OPEN && nowNanos >= openUntilNanos) {
                return State.HALF_OPEN;
            }
            return state;
        }

        private Permission allowedPermission(
                boolean halfOpenProbe
        ) {
            return new Permission(
                    true,
                    failoverAllowed,
                    generation,
                    halfOpenProbe
            );
        }

        private Permission deniedPermission() {
            return new Permission(
                    false,
                    failoverAllowed,
                    generation,
                    false
            );
        }

        private boolean isCurrent(
                Permission permission
        ) {
            return permission != null
                    && permission.allowed()
                    && permission.generation() == generation;
        }

        private boolean isOlderPermission(
                Permission permission
        ) {
            return permission != null
                    && permission.allowed()
                    && permission.generation() < generation;
        }

        private void openCircuit(
                long nowNanos,
                long durationNanos
        ) {
            state = State.OPEN;
            openUntilNanos = nowNanos + durationNanos;
            halfOpenProbeInFlight = false;

            // 使当前状态周期内已经放行、但尚未返回的请求全部过期。
            generation++;
        }

        private void closeCircuit() {
            state = State.CLOSED;
            consecutiveFailures = 0;
            openUntilNanos = 0;
            halfOpenProbeInFlight = false;
            failoverAllowed = true;

            // 使 HALF_OPEN 探测周期的许可失效。
            generation++;
        }
    }
}
