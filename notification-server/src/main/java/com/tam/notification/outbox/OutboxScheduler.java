package com.tam.notification.outbox;

import com.tam.notification.common.tenant.TenantContext;
import com.tam.notification.domain.outbox.OutboxEvent;
import com.tam.notification.domain.outbox.OutboxRepository;
import com.tam.notification.mq.RocketMQEventPublisher;
import com.tam.notification.service.OutboxPublishResultService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OutboxScheduler {
    private final OutboxRepository outboxRepository;
    private final RocketMQEventPublisher eventPublisher;
    private final OutboxPublishResultService resultService;

    @Value("${notification.outbox.batch-size:100}")
    private Integer batchSize;

    @Scheduled(fixedDelayString = "${notification.outbox.publish-interval-ms:1000}")
    public void publish() {
        final var events = outboxRepository.findPending(batchSize);
        for (OutboxEvent event : events) {
            publishOne(event);
        }
    }

    private void publishOne(final OutboxEvent event) {
        try {
            TenantContext.setTenantId(event.getTenantId());
            eventPublisher.publish(event.getTopic(), event.getPayload());
            resultService.markPublished(event);
        } catch (Exception e) {
            resultService.markFailed(event, e);
        } finally {
            TenantContext.clear();
        }
    }
}
