package com.tam.notification.outbox;

import com.tam.notification.common.tenant.TenantContext;
import com.tam.notification.config.ServerSchedulingConfig;
import com.tam.notification.domain.outbox.OutboxEvent;
import com.tam.notification.domain.outbox.OutboxRepository;
import com.tam.notification.mq.RocketMQEventPublisher;
import com.tam.notification.service.OutboxPublishResultService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxScheduler {
    private final OutboxRepository outboxRepository;
    private final RocketMQEventPublisher eventPublisher;
    private final OutboxPublishResultService resultService;

    private final String publisherId = UUID.randomUUID().toString().replace("-", "");

    @Value("${notification.outbox.batch-size:100}")
    private Integer batchSize;

    @Value("${notification.outbox.claim-timeout-seconds:30}")
    private Long claimTimeoutSeconds;

    @Scheduled(
            fixedDelayString = "${notification.outbox.publish-interval-ms:1000}",
            scheduler = ServerSchedulingConfig.OUTBOX_TASK_SCHEDULER
    )
    public void publish() {

        // 超时时间
        LocalDateTime expiredBefore = LocalDateTime.now().minusSeconds(claimTimeoutSeconds);

        // 这里只负责发现候选事件，查询出来不代表可以获得执行权
        final var events = outboxRepository.findClaimable(batchSize, expiredBefore);
        for (OutboxEvent event : events) {
            publishOne(event, expiredBefore);
        }
    }

    private void publishOne(final OutboxEvent event, LocalDateTime expiredBefore) {
        try {
            TenantContext.setTenantId(event.getTenantId());

            // cas抢执行权
            boolean claimed = outboxRepository.tryClaim(event.getId(), publisherId, expiredBefore);

            if (!claimed) {
                log.debug("Outbox已被其他实例抢占， eventId = {}", event.getId());
                return;
            }

            try {
                // 真正外部发送
                eventPublisher.publish(event.getTopic(), event.getPayload());
                resultService.markPublished(event, publisherId);
            } catch (Exception e) {
                resultService.markFailed(event, publisherId, e);
            }
        } catch (Exception e) {
            log.error("Outbox处理异常, eventId={}", event.getEventId(), e);
        } finally {
            TenantContext.clear();
        }
    }
}
