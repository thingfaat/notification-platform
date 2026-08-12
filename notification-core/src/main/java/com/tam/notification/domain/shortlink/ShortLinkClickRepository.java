package com.tam.notification.domain.shortlink;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 定义统计仓储接口
 */
public interface ShortLinkClickRepository {

    boolean trySaveClick(ShortLinkClick click);

    boolean tryRegisterDailyVisitor(
            Long shortLinkId,
            LocalDate statDate,
            String visitorKey,
            LocalDateTime firstClickedAt
    );

    void incrementDailyStat(
            Long shortLinkId,
            LocalDate statDate,
            Long pvDelta,
            Long uvDelta
    );

    List<ShortLinkDailyStat> findDailyStats(
            Long shortLinkId,
            LocalDate startDate,
            LocalDate endDate
    );
}
