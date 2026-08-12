package com.tam.notification.domain.shortlink;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日统计结果
 *
 * @param shortLinkId
 * @param statDate
 * @param pv
 * @param uv
 * @param updatedAt
 */
public record ShortLinkDailyStat(
        Long shortLinkId,
        LocalDate statDate,
        Long pv,
        Long uv,
        LocalDateTime updatedAt
) {
}
