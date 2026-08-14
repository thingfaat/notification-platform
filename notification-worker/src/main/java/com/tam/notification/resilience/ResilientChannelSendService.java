package com.tam.notification.resilience;


import com.tam.notification.channel.ChannelSenderRouter;
import com.tam.notification.domain.channel.ChannelSendCommand;
import com.tam.notification.domain.channel.ChannelSendResult;
import com.tam.notification.domain.channel.ChannelSendResultType;
import com.tam.notification.domain.channel.ChannelSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 渠道调用服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResilientChannelSendService {

    // 渠道路由
    private final ChannelSenderRouter senderRouter;

    // 渠道调用执行器
    private final ChannelCallExecutor callExecutor;

    // 渠道熔断器
    private final ChannelCircuitBreaker circuitBreaker;

    /**
     * 发送渠道消息
     *
     * @param command
     * @return
     */
    public ChannelSendResult send(ChannelSendCommand command) {
        ChannelSendResult lastRetryableFailure = null;

        for (ChannelSender sender : senderRouter.routeCandidates(command.channelType())) {

            ChannelCircuitBreaker.CircuitKey key = new ChannelCircuitBreaker.CircuitKey(
                    sender.channelType(),
                    sender.providerCode()
            );

            final var permission = circuitBreaker.tryAcquire(key); // 获取渠道调用权限
            if (!permission.allowed()) { // 熔断
                if (permission.failoverAllowed()) { // 允许安全切换
                    log.warn("渠道熔断，安全切换备用供应商，providerCode={}， messageId = {}", sender.providerCode(), command.messageId());
                    continue;
                }
                /*
                 * 熔断器由结果未知异常打开。
                 * 不允许盲目切备用。
                 */
                throw ChannelResilienceException.circuitOpen(
                        sender.providerCode(),
                        false
                );
            }

            // 允许渠道调用
            ChannelSendResult result;
            try {
                result = callExecutor.execute(sender, command);
            } catch (ChannelResilienceException exception) {
                // 记录熔断类型
                if (exception.getType() == ChannelResilienceException.Type.ISOLATION_REJECTED) { // 隔离拒绝
                    circuitBreaker.recordNeutral(key, permission);
                } else { // 未知异常
                    circuitBreaker.recordUnknownFailure(key, permission);
                }
                /*
                 * 超时、线程中断和运行时异常都无法确认
                 * 是否已经发送。
                 *
                 * 不切备用，交给RocketMQ使用同一个
                 * eventId和idempotencyKey重新投递。
                 */
                throw exception;
            } catch (RuntimeException exception) {
                circuitBreaker.recordUnknownFailure(key, permission);
                throw exception;
            }

            if (result.type() == ChannelSendResultType.RETRYABLE_FAILURE) {
                circuitBreaker.recordDefinitiveFailure(key, permission);

                lastRetryableFailure = result;

                log.warn("主渠道明确拒绝请求，尝试备用供应商，providerCode={}, messageId={}, errorCode={}",
                        sender.providerCode(),
                        command.messageId(),
                        result.errorCode()
                );

                continue;
            }

            /*
             * SUCCESS和PERMANENT_FAILURE都代表
             * 供应商给出了明确响应，供应商本身可达。
             */
            circuitBreaker.recordSuccess(key, permission);

            return result;
        }

        // 所有渠道供应商均不可用，如果存在一个RetryableFailure，则返回它
        return Objects.requireNonNullElseGet(lastRetryableFailure, () -> ChannelSendResult.retryableFailure(
                "ALL_PROVIDERS_UNAVAILABLE",
                "所有渠道供应商当前均不可用"
        ));
    }
}
