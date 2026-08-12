package com.tam.notification.controller;

import com.tam.notification.common.web.ApiResponse;
import com.tam.notification.shortlink.service.ShortLinkStatisticsService;
import com.tam.notification.vo.ShortLinkDailyStatResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/short-links")
@RequiredArgsConstructor
public class ShortLinkStatisticsController {

    private final ShortLinkStatisticsService statisticsService;

    @GetMapping("/{shortLinkId}/statistics")
    public ApiResponse<List<ShortLinkDailyStatResponse>>
    queryDaily(
            @PathVariable Long shortLinkId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
    ) {
        List<ShortLinkDailyStatResponse> result = statisticsService.queryDaily(
                        shortLinkId,
                        startDate,
                        endDate
                )
                .stream()
                .map(ShortLinkDailyStatResponse::from)
                .toList();

        return ApiResponse.success(result);
    }
}
