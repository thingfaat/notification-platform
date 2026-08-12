package com.tam.notification.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.tam.notification.common.tenant.TenantContext;
import com.tam.notification.domain.shortlink.ShortLinkClick;
import com.tam.notification.domain.shortlink.ShortLinkClickRepository;
import com.tam.notification.domain.shortlink.ShortLinkDailyStat;
import com.tam.notification.persistence.entity.ShortLinkClickDO;
import com.tam.notification.persistence.entity.ShortLinkDailyVisitorDO;
import com.tam.notification.persistence.mapper.ShortLinkClickMapper;
import com.tam.notification.persistence.mapper.ShortLinkDailyStatMapper;
import com.tam.notification.persistence.mapper.ShortLinkDailyVisitorMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class ShortLinkClickRepositoryImpl implements ShortLinkClickRepository {

    private final ShortLinkClickMapper clickMapper;
    private final ShortLinkDailyVisitorMapper visitorMapper;
    private final ShortLinkDailyStatMapper statMapper;

    @Override
    public boolean trySaveClick(final ShortLinkClick click) {
        ShortLinkClickDO data = new ShortLinkClickDO();

        data.setId(IdWorker.getId());
        data.setTenantId(TenantContext.requireTenantId());
        data.setEventId(click.getEventId());
        data.setShortLinkId(click.getShortLinkId());
        data.setShortCode(click.getShortCode());
        data.setVisitorKey(click.getVisitorKey());
        data.setClickedAt(click.getClickedAt());

        return clickMapper.insertIgnore(data) == 1;
    }

    @Override
    public boolean tryRegisterDailyVisitor(final Long shortLinkId, final LocalDate statDate, final String visitorKey, final LocalDateTime firstClickedAt) {
        ShortLinkDailyVisitorDO data = new ShortLinkDailyVisitorDO();

        data.setId(IdWorker.getId());
        data.setTenantId(TenantContext.requireTenantId());
        data.setShortLinkId(shortLinkId);
        data.setStatDate(statDate);
        data.setVisitorKey(visitorKey);
        data.setFirstClickedAt(firstClickedAt);

        return visitorMapper.insertIgnore(data) == 1;
    }

    @Override
    public void incrementDailyStat(final Long shortLinkId, final LocalDate statDate, final Long pvDelta, final Long uvDelta) {
        int affectedRows = statMapper.incrementDaily(
                IdWorker.getId(),
                TenantContext.requireTenantId(),
                shortLinkId,
                statDate,
                pvDelta,
                uvDelta
        );

        if (affectedRows <= 0) {
            throw new IllegalStateException("increment short-link daily stat failed");
        }
    }

    @Override
    public List<ShortLinkDailyStat> findDailyStats(final Long shortLinkId, final LocalDate startDate, final LocalDate endDate) {
        return statMapper
                .selectDaily(
                        shortLinkId,
                        startDate,
                        endDate
                )
                .stream()
                .map(data -> new ShortLinkDailyStat(
                        data.getShortLinkId(),
                        data.getStatDate(),
                        data.getPv(),
                        data.getUv(),
                        data.getUpdatedAt()
                ))
                .toList();
    }
}
