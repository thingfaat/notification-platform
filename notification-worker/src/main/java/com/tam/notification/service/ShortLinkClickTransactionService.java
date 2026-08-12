package com.tam.notification.service;

import com.tam.notification.domain.shortlink.ShortLinkClick;
import com.tam.notification.domain.shortlink.ShortLinkClickEvent;
import com.tam.notification.domain.shortlink.ShortLinkClickRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class ShortLinkClickTransactionService {

    private final ShortLinkClickRepository clickRepository;

    @Transactional
    public void record(ShortLinkClickEvent event) {
        final var click = new ShortLinkClick();
        click.setTenantId(event.tenantId());
        click.setEventId(event.eventId());
        click.setShortLinkId(event.shortLinkId());
        click.setShortCode(event.shortCode());
        click.setVisitorKey(event.visitorKey());
        click.setClickedAt(event.occurredAt());

        // evenId已经存在，说明mq重复投递，不再增加pv和uv
        boolean firstConsume = clickRepository.trySaveClick(click);
        if (!firstConsume) {
            return;
        }

        LocalDate statDate = event.occurredAt().toLocalDate();

        boolean newVisitor = clickRepository.tryRegisterDailyVisitor(
                event.shortLinkId(),
                statDate,
                event.visitorKey(),
                event.occurredAt()
        );

        clickRepository.incrementDailyStat(
                event.shortLinkId(),
                statDate,
                1L,
                newVisitor ? 1L : 0L
        );
    }
}
