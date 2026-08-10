package com.tam.notification.persistence.repository;

import com.tam.notification.domain.enums.ShortLinkStatus;
import com.tam.notification.domain.shortlink.ShortLink;
import com.tam.notification.domain.shortlink.ShortLinkRepository;
import com.tam.notification.persistence.entity.ShortLinkDO;
import com.tam.notification.persistence.mapper.ShortLinkMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ShortLinkRepositoryImpl implements ShortLinkRepository {

    private final ShortLinkMapper shortLinkMapper;


    @Override
    public ShortLink save(final ShortLink shortLink) {

        ShortLinkDO data = toDO(shortLink);

        shortLinkMapper.insert(data);

        shortLink.setId(data.getId());
        shortLink.setTenantId(data.getTenantId());
        return shortLink;
    }

    @Override
    public Optional<ShortLink> findById(final Long id) {
        final var data = shortLinkMapper.selectById(id);

        return Optional.ofNullable(data)
                .map(this::toDomain);
    }

    private ShortLinkDO toDO(ShortLink shortLink) {
        ShortLinkDO data = new ShortLinkDO();

        data.setId(shortLink.getId());
        data.setTenantId(shortLink.getTenantId());
        data.setApplicationId(shortLink.getApplicationId());
        data.setOriginalUrl(shortLink.getOriginalUrl());
        data.setExpireAt(shortLink.getExpireAt());
        data.setVersion(shortLink.getVersion());

        if (shortLink.getStatus() != null) {
            data.setStatus(shortLink.getStatus().name());
        }

        return data;
    }

    private ShortLink toDomain(ShortLinkDO shortLinkDO) {
        final var shortLink = new ShortLink();

        shortLink.setId(shortLinkDO.getId());
        shortLink.setTenantId(shortLinkDO.getTenantId());
        shortLink.setApplicationId(shortLinkDO.getApplicationId());
        shortLink.setOriginalUrl(shortLinkDO.getOriginalUrl());
        shortLink.setExpireAt(shortLinkDO.getExpireAt());
        shortLink.setCreatedAt(shortLinkDO.getCreatedAt());
        shortLink.setUpdatedAt(shortLinkDO.getUpdatedAt());

        if (shortLinkDO.getStatus() != null) {
            shortLink.setStatus(ShortLinkStatus.valueOf(shortLinkDO.getStatus()));
        }

        return shortLink;
    }
}
