package com.tam.notification.service;

import com.tam.notification.common.tenant.TenantContext;
import com.tam.notification.common.trace.TraceContext;
import com.tam.notification.domain.message.NotificationMessage;
import com.tam.notification.domain.message.NotificationMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class RetryScheduler {
    private final NotificationMessageRepository messageRepository;
    private final RetryScheduleService retryScheduleService;


    @Value("${notification.retry.batch-size:100}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${notification.retry.scan-interval-ms:1000}")
    public void retry() {
        List<NotificationMessage> messages = messageRepository.findRetryableAcrossTenants(batchSize, LocalDateTime.now());

        for (final var message : messages) {
            retryOne(message);
        }
    }

    private void retryOne(NotificationMessage message) {
        try {
            TenantContext.setTenantId(message.getTenantId());
            TraceContext.setTraceId(UUID.randomUUID().toString().replace("-", ""));

            boolean success = retryScheduleService.requeue(message.getId());
            if (success) {
                log.info("消息进入下一次发送, messageId={}", message.getId());
            }
        } catch (Exception e) {
            log.error("重试调度失败, messageId={}", message.getId(), e);
        } finally {
            TraceContext.clear();
            TenantContext.clear();
        }
    }
}
