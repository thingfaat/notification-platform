package com.tam.notification.resilience;

import com.tam.notification.common.tenant.TenantContext;
import com.tam.notification.common.trace.TraceContext;
import com.tam.notification.domain.channel.ChannelSendCommand;
import com.tam.notification.domain.channel.ChannelSendResult;
import com.tam.notification.domain.channel.ChannelSender;
import com.tam.notification.domain.enums.ChannelType;
import jakarta.annotation.PreDestroy;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 渠道调用执行器
 */
@Component
public class ChannelCallExecutor {

    private final Map<ChannelType, ThreadPoolExecutor> executors;

    private final Duration callTimeout;

    public ChannelCallExecutor(ChannelResilienceProperties properties) {
        validate(properties);

        this.callTimeout = properties.getCallTimeout();

        final var pools = new EnumMap<ChannelType, ThreadPoolExecutor>(ChannelType.class);

        for (final var channelType : ChannelType.values()) {
            pools.put(
                    channelType,
                    new ThreadPoolExecutor(
                            properties.getCorePoolSize(), // 核心线程数
                            properties.getMaxPoolSize(), // 最大线程数
                            properties.getKeepAlive().toMillis(), // 线程空闲时间
                            TimeUnit.MILLISECONDS, // 空闲时间单位
                            new ArrayBlockingQueue<>(properties.getQueueCapacity()), // 等待任务队列
                            new NamedThreadFactory("channel-" + channelType.name().toLowerCase() + "-"), // 线程名
                            new ThreadPoolExecutor.AbortPolicy() // 队列满拒绝策略
                    )
            );
        }

        this.executors = Map.copyOf(pools);
    }

    /**
     * 渠道调用
     * @param sender
     * @param command
     * @return
     */
    public ChannelSendResult execute(
            ChannelSender sender,
            ChannelSendCommand command
    ) {
        final var executor = executors.get(sender.channelType());

        // mq 消费线程中treadLocal/mdc不会自动传入渠道线程池，所以必须显示捕获和恢复
        final var tenantId = TenantContext.getTenantId();
        String traceId = TraceContext.getTraceId();

        Future<ChannelSendResult> future;

        try {
            future = executor.submit(() -> {
                try {
                    if (tenantId != null) {
                        TenantContext.setTenantId(tenantId);
                    }
                    if (traceId != null) {
                        TraceContext.setTraceId(traceId);
                    }
                    return sender.send(command);
                } finally {
                    // 线程池线程会被重复使用，不清理会导致租户和Trace串线
                    TenantContext.clear();
                    TraceContext.clear();
                }
            });
        } catch (RejectedExecutionException exception) { // 隔离拒绝
            throw ChannelResilienceException.isolationRejected(sender.providerCode(), exception);
        }

        try {
            return future.get(callTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) { // 超时
            future.cancel(true);
            throw ChannelResilienceException.timeout(sender.providerCode());
        } catch (InterruptedException exception) { // 中断
            future.cancel(true);
            Thread.currentThread().interrupt();

            throw ChannelResilienceException.interrupted(
                    sender.providerCode(),
                    exception
            );
        } catch (ExecutionException exception) { // 运行时异常
            Throwable cause = exception.getCause();

            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }

            if (cause instanceof Error error) {
                throw error;
            }

            throw new IllegalStateException(
                    "渠道调用异常，providerCode=" + sender.providerCode(),
                    cause
            );
        }
    }

    @PreDestroy
    public void close() {
        executors.values().forEach(ThreadPoolExecutor::shutdownNow);
    }

    private void validate(ChannelResilienceProperties properties) {
        if (properties.getCorePoolSize() <= 0
                || properties.getMaxPoolSize()
                < properties.getCorePoolSize()) {
            throw new IllegalStateException(
                    "渠道线程池大小配置非法"
            );
        }

        if (properties.getQueueCapacity() <= 0) {
            throw new IllegalStateException("渠道线程池队列容量必须大于0");
        }

        validatePositive(
                properties.getKeepAlive(),
                "keepAlive"
        );

        validatePositive(
                properties.getCallTimeout(),
                "callTimeout"
        );
    }

    private void validatePositive(
            Duration duration,
            String name
    ) {
        if (duration == null
                || duration.isZero()
                || duration.isNegative()) {
            throw new IllegalStateException(
                    name + "必须大于0"
            );
        }
    }

    /**
     * 线程工厂
     */
    private static final class NamedThreadFactory implements ThreadFactory {
        private final String prefix;

        private final AtomicInteger sequence = new AtomicInteger();

        private NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }


        @Override
        public Thread newThread(@NonNull final Runnable runnable) {
            return new Thread(
                    runnable,
                    prefix + "-" + sequence.getAndIncrement()
            );
        }
    }
}
