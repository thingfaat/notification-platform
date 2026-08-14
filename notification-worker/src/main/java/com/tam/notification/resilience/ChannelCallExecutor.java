package com.tam.notification.resilience;

import com.tam.notification.common.tenant.TenantContext;
import com.tam.notification.common.trace.TraceContext;
import com.tam.notification.domain.channel.ChannelSendCommand;
import com.tam.notification.domain.channel.ChannelSendResult;
import com.tam.notification.domain.channel.ChannelSender;
import com.tam.notification.domain.enums.ChannelType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
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
 * <p>
 * 每一种ChannelType使用独立线程池，防止一个渠道阻塞其他渠道
 */
@Component
public class ChannelCallExecutor {

    // 渠道线程池活动线程池
    private static final String EXECUTOR_ACTIVE = "notification.channel.executor.active";

    // 渠道线程池当前线程数
    private static final String EXECUTOR_POOL_SIZE = "notification.channel.executor.pool.size";

    // 渠道线程池等待队列长度
    private static final String EXECUTOR_QUEUED = "notification.channel.executor.queued";

    // 渠道线程池队列剩余容量
    private static final String EXECUTOR_QUEUE_REMAINING = "notification.channel.executor.queue.remaining";

    // 渠道线程池已完成任务数
    private static final String EXECUTOR_COMPLETED = "notification.channel.executor.completed";

    // 渠道隔离线程池拒绝任务数
    private static final String EXECUTOR_REJECTED = "notification.channel.executor.rejected";

    private final Map<ChannelType, ThreadPoolExecutor> executors;

    private final Duration callTimeout;

    public ChannelCallExecutor(
            ChannelResilienceProperties properties,
            MeterRegistry meterRegistry
    ) {
        validate(properties);

        this.callTimeout = properties.getCallTimeout();

        final var pools = new EnumMap<ChannelType, ThreadPoolExecutor>(ChannelType.class);

        for (final var channelType : ChannelType.values()) {

            Counter rejectedCounter = Counter.builder(EXECUTOR_REJECTED)
                    .description("渠道隔离线程池拒绝任务数")
                    .tag("channel", channelTag(channelType))
                    .register(meterRegistry);


            final var executor = new ThreadPoolExecutor(
                    properties.getCorePoolSize(), // 核心线程数
                    properties.getMaxPoolSize(), // 最大线程数
                    properties.getKeepAlive().toMillis(), // 线程空闲时间
                    TimeUnit.MILLISECONDS, // 空闲时间单位
                    new ArrayBlockingQueue<>(properties.getQueueCapacity()), // 等待任务队列
                    new NamedThreadFactory("channel-" + channelType.name().toLowerCase() + "-"), // 线程名
                    new MeteredAbortPolicy(rejectedCounter) // 队列满拒绝策略
            );

            bindExecutorMetrics(
                    meterRegistry,
                    channelType,
                    executor
            );

            pools.put(
                    channelType,
                    executor
            );
        }

        this.executors = Map.copyOf(pools);
    }

    /**
     * 渠道调用
     *
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

    private void bindExecutorMetrics(
            MeterRegistry meterRegistry,
            ChannelType channelType,
            ThreadPoolExecutor executor
    ) {
        String channel = channelTag(channelType);

        Gauge.builder(EXECUTOR_ACTIVE, executor, ThreadPoolExecutor::getActiveCount)
                .description("渠道线程池活动线程池")
                .tag("channel", channel)
                .register(meterRegistry);

        Gauge.builder(EXECUTOR_POOL_SIZE, executor, ThreadPoolExecutor::getPoolSize)
                .description("渠道线程池当前线程数")
                .tag("channel", channel)
                .register(meterRegistry);

        Gauge.builder(EXECUTOR_QUEUED, executor, pool -> pool.getQueue().size())
                .description("渠道线程池等待队列长度")
                .tag("channel", channel)
                .register(meterRegistry);

        Gauge.builder(EXECUTOR_QUEUE_REMAINING, executor, pool -> pool.getQueue().remainingCapacity())
                .description("渠道线程池队列剩余容量")
                .tag("channel", channel)
                .register(meterRegistry);

        FunctionCounter.builder(EXECUTOR_COMPLETED, executor, pool -> pool.getCompletedTaskCount())
                .description("渠道线程池已完成任务数")
                .tag("channel", channel)
                .register(meterRegistry);
    }

    private String channelTag(ChannelType channelType) {
        return channelType.name().toLowerCase();
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
     * 使用监听器，统计拒绝
     */
    private static final class MeteredAbortPolicy implements RejectedExecutionHandler {
        // 当前线程拒绝统计
        private final Counter rejectedCounter;

        // 使用的拒绝策略，抛出RejectedExecutionException
        private final ThreadPoolExecutor.AbortPolicy delegate = new ThreadPoolExecutor.AbortPolicy();

        private MeteredAbortPolicy(
                Counter rejectedCounter
        ) {
            this.rejectedCounter = rejectedCounter;
        }

        @Override
        public void rejectedExecution(final Runnable runnable, final ThreadPoolExecutor executor) {
            rejectedCounter.increment(); // 增加统计数量
            delegate.rejectedExecution(runnable, executor); // 调用原来的代理的拒绝策略
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
