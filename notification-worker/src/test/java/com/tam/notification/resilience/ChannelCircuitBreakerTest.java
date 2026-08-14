package com.tam.notification.resilience;

import com.tam.notification.domain.enums.ChannelType;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelCircuitBreakerTest {

    @Test
    void staleSuccessMustNotCloseNewlyOpenedCircuit() {
        ChannelCircuitBreaker breaker = breaker(
                2,
                Duration.ofMinutes(1)
        );
        ChannelCircuitBreaker.CircuitKey key = key(
                ChannelType.SMS,
                "primary"
        );

        // 模拟三个请求在 CLOSED 状态下并发取得许可。
        ChannelCircuitBreaker.Permission first =
                breaker.tryAcquire(key);
        ChannelCircuitBreaker.Permission second =
                breaker.tryAcquire(key);
        ChannelCircuitBreaker.Permission stale =
                breaker.tryAcquire(key);

        assertEquals(first.generation(), second.generation());
        assertEquals(first.generation(), stale.generation());

        breaker.recordDefinitiveFailure(key, first);
        breaker.recordDefinitiveFailure(key, second);

        assertEquals(
                ChannelCircuitBreaker.State.OPEN,
                breaker.currentState(key)
        );

        // 熔断器打开后，旧 CLOSED 周期中的成功结果必须被忽略。
        breaker.recordSuccess(key, stale);

        assertEquals(
                ChannelCircuitBreaker.State.OPEN,
                breaker.currentState(key)
        );
        assertFalse(breaker.tryAcquire(key).allowed());
    }

    @Test
    void staleFailureMustNotPolluteRecoveredCircuit() throws Exception {
        ChannelCircuitBreaker breaker = breaker(
                1,
                Duration.ofMillis(10)
        );
        ChannelCircuitBreaker.CircuitKey key = key(
                ChannelType.SMS,
                "primary"
        );

        ChannelCircuitBreaker.Permission openingRequest =
                breaker.tryAcquire(key);
        ChannelCircuitBreaker.Permission staleRequest =
                breaker.tryAcquire(key);

        breaker.recordDefinitiveFailure(key, openingRequest);
        Thread.sleep(20);

        ChannelCircuitBreaker.Permission probe =
                breaker.tryAcquire(key);
        assertTrue(probe.allowed());
        assertTrue(probe.halfOpenProbe());

        breaker.recordSuccess(key, probe);
        assertEquals(
                ChannelCircuitBreaker.State.CLOSED,
                breaker.currentState(key)
        );

        // 旧周期中的未知失败不能把新周期标记为“禁止切备用”。
        breaker.recordUnknownFailure(key, staleRequest);

        ChannelCircuitBreaker.Permission current =
                breaker.tryAcquire(key);
        breaker.recordDefinitiveFailure(key, current);

        ChannelCircuitBreaker.Permission denied =
                breaker.tryAcquire(key);
        assertFalse(denied.allowed());
        assertTrue(denied.failoverAllowed());
    }

    @Test
    void staleUnknownFailureMustDisableFailoverWhileCircuitIsOpen() {
        ChannelCircuitBreaker breaker = breaker(
                1,
                Duration.ofMinutes(1)
        );
        ChannelCircuitBreaker.CircuitKey key = key(
                ChannelType.SMS,
                "primary"
        );

        ChannelCircuitBreaker.Permission definitiveRequest =
                breaker.tryAcquire(key);
        ChannelCircuitBreaker.Permission unknownRequest =
                breaker.tryAcquire(key);

        // 明确失败先返回并安全打开熔断器。
        breaker.recordDefinitiveFailure(key, definitiveRequest);
        assertTrue(breaker.tryAcquire(key).failoverAllowed());

        // 同一旧代次的另一个请求随后结果未知，必须禁止后续 MQ 重投切备用。
        breaker.recordUnknownFailure(key, unknownRequest);

        ChannelCircuitBreaker.Permission denied =
                breaker.tryAcquire(key);
        assertFalse(denied.allowed());
        assertFalse(denied.failoverAllowed());
    }

    @Test
    void halfOpenMustAllowOnlyOneProbeAndCloseOnSuccess()
            throws Exception {
        ChannelCircuitBreaker breaker = breaker(
                1,
                Duration.ofMillis(10)
        );
        ChannelCircuitBreaker.CircuitKey key = key(
                ChannelType.SMS,
                "primary"
        );

        ChannelCircuitBreaker.Permission initial =
                breaker.tryAcquire(key);
        breaker.recordDefinitiveFailure(key, initial);
        Thread.sleep(20);

        ChannelCircuitBreaker.Permission probe =
                breaker.tryAcquire(key);
        ChannelCircuitBreaker.Permission concurrent =
                breaker.tryAcquire(key);

        assertTrue(probe.allowed());
        assertTrue(probe.halfOpenProbe());
        assertFalse(concurrent.allowed());

        breaker.recordSuccess(key, probe);

        assertEquals(
                ChannelCircuitBreaker.State.CLOSED,
                breaker.currentState(key)
        );
        assertTrue(breaker.tryAcquire(key).allowed());
    }

    @Test
    void halfOpenFailureMustReopenCircuit() throws Exception {
        ChannelCircuitBreaker breaker = breaker(
                1,
                Duration.ofMillis(10)
        );
        ChannelCircuitBreaker.CircuitKey key = key(
                ChannelType.SMS,
                "primary"
        );

        ChannelCircuitBreaker.Permission initial =
                breaker.tryAcquire(key);
        breaker.recordDefinitiveFailure(key, initial);
        Thread.sleep(20);

        ChannelCircuitBreaker.Permission probe =
                breaker.tryAcquire(key);
        breaker.recordDefinitiveFailure(key, probe);

        assertEquals(
                ChannelCircuitBreaker.State.OPEN,
                breaker.currentState(key)
        );
        assertFalse(breaker.tryAcquire(key).allowed());
    }

    @Test
    void sameProviderCodeInDifferentChannelsMustUseDifferentCircuits() {
        ChannelCircuitBreaker breaker = breaker(
                1,
                Duration.ofMinutes(1)
        );
        ChannelCircuitBreaker.CircuitKey smsKey = key(
                ChannelType.SMS,
                "shared-provider"
        );
        ChannelCircuitBreaker.CircuitKey emailKey = key(
                ChannelType.EMAIL,
                "shared-provider"
        );

        ChannelCircuitBreaker.Permission smsPermission =
                breaker.tryAcquire(smsKey);
        breaker.recordUnknownFailure(smsKey, smsPermission);

        assertEquals(
                ChannelCircuitBreaker.State.OPEN,
                breaker.currentState(smsKey)
        );
        assertEquals(
                ChannelCircuitBreaker.State.CLOSED,
                breaker.currentState(emailKey)
        );
        assertTrue(breaker.tryAcquire(emailKey).allowed());
    }

    private ChannelCircuitBreaker breaker(
            int failureThreshold,
            Duration openDuration
    ) {
        ChannelResilienceProperties properties =
                new ChannelResilienceProperties();
        properties.setFailureThreshold(failureThreshold);
        properties.setOpenDuration(openDuration);
        return new ChannelCircuitBreaker(properties);
    }

    private ChannelCircuitBreaker.CircuitKey key(
            ChannelType channelType,
            String providerCode
    ) {
        return new ChannelCircuitBreaker.CircuitKey(
                channelType,
                providerCode
        );
    }
}
