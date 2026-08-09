package com.tam.notification.shortlink.persistence.repository;

import com.tam.notification.shortlink.domain.ShortLink;
import com.tam.notification.shortlink.persistence.entity.ShortLinkDO;
import com.tam.notification.shortlink.persistence.entity.ShortLinkMappingDO;
import com.tam.notification.shortlink.persistence.mapper.ShortLinkMapper;
import com.tam.notification.shortlink.repository.ShortLinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

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
}
