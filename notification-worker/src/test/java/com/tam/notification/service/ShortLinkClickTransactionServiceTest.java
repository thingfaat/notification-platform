package com.tam.notification.service;

import com.tam.notification.domain.shortlink.ShortLinkClick;
import com.tam.notification.domain.shortlink.ShortLinkClickEvent;
import com.tam.notification.domain.shortlink.ShortLinkClickRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShortLinkClickTransactionServiceTest {

    @Mock
    private ShortLinkClickRepository repository;

    @InjectMocks
    private ShortLinkClickTransactionService service;

    @Test
    void shouldIncreasePvAndUvForNewVisitor() {
        ShortLinkClickEvent event = event();

        when(repository.trySaveClick(
                any(ShortLinkClick.class)
        )).thenReturn(true);

        when(repository.tryRegisterDailyVisitor(
                eq(30001L),
                eq(LocalDate.of(2026, 8, 12)),
                eq("visitor-key"),
                eq(event.occurredAt())
        )).thenReturn(true);

        service.record(event);

        verify(repository).incrementDailyStat(
                30001L,
                LocalDate.of(2026, 8, 12),
                1L,
                1L
        );
    }

    @Test
    void shouldOnlyIncreasePvForReturningVisitor() {
        ShortLinkClickEvent event = event();

        when(repository.trySaveClick(
                any(ShortLinkClick.class)
        )).thenReturn(true);

        when(repository.tryRegisterDailyVisitor(
                eq(30001L),
                eq(LocalDate.of(2026, 8, 12)),
                eq("visitor-key"),
                eq(event.occurredAt())
        )).thenReturn(false);

        service.record(event);

        verify(repository).incrementDailyStat(
                30001L,
                LocalDate.of(2026, 8, 12),
                1L,
                0L
        );
    }

    @Test
    void shouldIgnoreDuplicatedEvent() {
        ShortLinkClickEvent event = event();

        when(repository.trySaveClick(
                any(ShortLinkClick.class)
        )).thenReturn(false);

        service.record(event);

        verify(repository, never())
                .tryRegisterDailyVisitor(
                        any(),
                        any(),
                        any(),
                        any()
                );

        verify(repository, never())
                .incrementDailyStat(
                        any(),
                        any(),
                        any(Long.class),
                        any(Long.class)
                );
    }

    private ShortLinkClickEvent event() {
        return new ShortLinkClickEvent(
                "event-001",
                10001L,
                30001L,
                "aZ8k2LmP",
                "visitor-key",
                "trace-001",
                LocalDateTime.of(
                        2026,
                        8,
                        12,
                        10,
                        30
                )
        );
    }
}
