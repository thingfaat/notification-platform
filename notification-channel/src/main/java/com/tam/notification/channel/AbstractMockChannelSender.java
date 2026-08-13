package com.tam.notification.channel;

import com.tam.notification.domain.channel.ChannelSendCommand;
import com.tam.notification.domain.channel.ChannelSendResult;
import com.tam.notification.domain.channel.ChannelSender;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 测试用抽象类
 */
public abstract class AbstractMockChannelSender implements ChannelSender {

    /**
     * 模拟真实供应商的幂等能力，同一个idempotencyKey重复调用，返回同一个发送结果
     */
    private final ConcurrentMap<String, ChannelSendResult> resultCache = new ConcurrentHashMap<>();

    /**
     * 用于异常实验：exception-once:xxx
     * 第一次调用抛出异常，第二次恢复
     */
    private final Set<String> exceptionOnceKeys = ConcurrentHashMap.newKeySet();

    @Override
    public ChannelSendResult send(final ChannelSendCommand command) {
        final var receiver = command.receiver();

        /*
         * 指定供应商明确拒绝请求
         * 示例：provider-retryable:mock-sms-primary:13800138000
         */
        if (receiver.startsWith("provider-retryable:" + providerCode() + ":")) {
            return ChannelSendResult.retryableFailure(
                    "PROVIDER_UNAVAILABLE",
                    "模拟指定供应商明确拒绝请求"
            );
        }

        /*
         * 指定供应商调用结果未知。
         */
        if (receiver.startsWith("provider-exception:" + providerCode() + ":")) {
            throw new IllegalStateException("模拟指定供应商调用结果未知");
        }

        /*
         * 指定供应商响应缓慢。
         */
        if (receiver.startsWith("provider-slow:" + providerCode() + ":")) {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("模拟渠道调用被中断", exception);
            }
        }

        /*
         * 模拟”模拟渠道持续不可用“，模拟渠道持续不可用
         */
        if (command.receiver().startsWith("exception-always:")) {
            throw new IllegalStateException("模拟渠道持续不可用");
        }

        /*
         * 模拟”调用结果未知“
         * 这里故意抛出异常，让RocketMQ重新投递同一个event
         */
        if (receiver.startsWith("exception-once:") && exceptionOnceKeys.add(command.idempotencyKey())) {
            throw new IllegalStateException("模拟渠道调用结果未知");
        }

        /*
         * 同一个发送的attempt始终返回同一结果
         */
        return resultCache.computeIfAbsent(command.idempotencyKey(), key -> doSend(command));
    }

    private ChannelSendResult doSend(final ChannelSendCommand command) {
        String receiver = command.receiver();

        // 永久失败
        if (receiver.startsWith("fail:")) {
            return ChannelSendResult.permanentFailure("INVALID_RECEIVER", "模拟接受地址非法");
        }

        // 第1、2次发送失败，第3次成功
        if (receiver.startsWith("retry:") && command.attemptNo() < 3) {
            return ChannelSendResult.retryableFailure("PROVIDER_BUSY", "模拟渠道繁忙");
        }
        return ChannelSendResult.success("MOCK_" + UUID.randomUUID()
                .toString()
                .replace("-", ""));
    }
}
