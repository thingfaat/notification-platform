package com.tam.notification.shortlink.service;

import com.tam.notification.common.exception.BusinessException;
import com.tam.notification.common.exception.CommonErrorCode;
import com.tam.notification.domain.shortlink.ShortLinkClickRepository;
import com.tam.notification.domain.shortlink.ShortLinkDailyStat;
import com.tam.notification.domain.shortlink.ShortLinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 统计查询服务
 */
@Service
@RequiredArgsConstructor
public class ShortLinkStatisticsService {

    private final ShortLinkRepository shortLinkRepository;
    private final ShortLinkClickRepository clickRepository;

    @Transactional(readOnly = true)
    public List<ShortLinkDailyStat> queryDaily(
            Long shortLinkId,
            LocalDate startDate,
            LocalDate endDate
    ) {
        validateRange(
                shortLinkId,
                startDate,
                endDate
        );

        /*
         * ShortLinkRepository 自动经过租户拦截器。
         * 当前租户看不到其他租户的短链统计。
         */
        shortLinkRepository
                .findById(shortLinkId)
                .orElseThrow(() -> new BusinessException(
                        CommonErrorCode.BUSINESS_ERROR,
                        "短链不存在"
                ));

        return clickRepository.findDailyStats(
                shortLinkId,
                startDate,
                endDate
        );
    }

    private void validateRange(
            Long shortLinkId,
            LocalDate startDate,
            LocalDate endDate
    ) {
        if (shortLinkId == null) {
            throw invalidParameter("shortLinkId不能为空");
        }

        if (startDate == null || endDate == null) {
            throw invalidParameter("开始日期和结束日期不能为空");
        }

        if (startDate.isAfter(endDate)) {
            throw invalidParameter("开始日期不能晚于结束日期");
        }

        long days = ChronoUnit.DAYS.between(
                startDate,
                endDate
        );

        if (days >= 31) {
            throw invalidParameter("单次最多查询31天");
        }
    }

    private BusinessException invalidParameter(
            String message
    ) {
        return new BusinessException(
                CommonErrorCode.INVALID_PARAMETER,
                message
        );
    }
}
