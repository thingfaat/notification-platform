package com.tam.notification.vo;

import com.tam.notification.domain.shortlink.ShortLinkDailyStat;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record ShortLinkDailyStatResponse(
        Long shortLinkId,
        LocalDate statDate,
        long pv,
        long uv,
        LocalDateTime updatedAt
) {
    public static ShortLinkDailyStatResponse from(
            ShortLinkDailyStat stat
    ) {
        return new ShortLinkDailyStatResponse(
                stat.shortLinkId(),
                stat.statDate(),
                stat.pv(),
                stat.uv(),
                stat.updatedAt()
        );
    }
}
