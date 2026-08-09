package com.tam.notification.service;

import com.tam.notification.domain.outbox.ConsumeRecordRepository;
import com.tam.notification.domain.outbox.NotificationSendEvent;
import com.tam.notification.domain.ratelimit.RateLimitDecision;
import com.tam.notification.domain.ratelimit.RateLimitRequest;
import com.tam.notification.domain.ratelimit.RateLimiter;
import com.tam.notification.domain.task.NotificationTask;
import com.tam.notification.domain.task.NotificationTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationRateLimitService {
    private final RateLimiter rateLimiter;
    private final NotificationTaskRepository taskRepository;
    private final ConsumeRecordRepository consumeRecordRepository;
    private final MessageSendTransactionService transactionService;

    @Value("${notification.mq.consumer-group}")
    private String consumerGroup;

    @Value("${notification.rate-limit.fail-open:true}")
    private boolean failOpen;

    /**
     * 返回true表示继续渠道发送，返回false表示已经进入THROTTLED等待
     *
     * @param event
     * @return
     */
    public boolean allowOrDefer(NotificationSendEvent event) {
        /**
         * 已完成事件无需再次扣减令牌，
         * prepare()稍后会再次检查，以保证事务安全
         */
        if (consumeRecordRepository.exists(event.tenantId(), consumerGroup, event.eventId())) {
            return true;
        }
        NotificationTask task = taskRepository.findById(event.taskId()).orElseThrow(() -> new IllegalStateException("Task不存在：" + event.taskId()));
        RateLimitDecision decision;

        try {
            decision = rateLimiter.tryAcquire(
                    RateLimitRequest.oneToken(
                            event.eventId(),
                            event.tenantId(),
                            event.applicationId(),
                            task.getChannelType()
                    )
            );
        } catch (RuntimeException e) {
            if (failOpen) {
                log.error("redis限流异常，执行fail-open，eventId={}，messageId={}", event.eventId(), event.messageId(), e);
                return true;
            }
            throw e;
        }
        if (decision.allowed()) {
            log.debug("限流通过，eventId={}, remaining={}", event.eventId(), decision.remainingTokens());
            return true;
        }

        transactionService.deferRateLimited(event, consumerGroup, decision.retryAfterMillis());
        log.info("消息被限流, eventId={}, messageId={}, retryAfterMillis={}",
                event.eventId(),
                event.messageId(),
                decision.retryAfterMillis()
        );

        return false;
    }
}
