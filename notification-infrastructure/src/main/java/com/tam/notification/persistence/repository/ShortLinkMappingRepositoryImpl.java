package com.tam.notification.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.tam.notification.common.tenant.TenantContext;
import com.tam.notification.domain.shortlink.ShortLinkMapping;
import com.tam.notification.domain.shortlink.ShortLinkMappingRepository;
import com.tam.notification.persistence.entity.ShortLinkMappingDO;
import com.tam.notification.persistence.mapper.ShortLinkMappingMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ShortLinkMappingRepositoryImpl implements ShortLinkMappingRepository {

    private final ShortLinkMappingMapper mappingMapper;

    @Override
    public boolean trySave(final ShortLinkMapping mapping) {
        final var data = new ShortLinkMappingDO();

        data.setId(IdWorker.getId());
        data.setTenantId(TenantContext.requireTenantId());
        data.setShortLinkId(mapping.getShortLinkId());
        data.setShortCode(mapping.getShortCode());

        final var affectedRows = mappingMapper.insertIgnore(data);

        if (affectedRows == 1) {
            mapping.setId(data.getId());
            mapping.setTenantId(data.getTenantId());
            return true;
        }
        return false;
    }

    @Override
    public Optional<ShortLinkMapping> findByShortCodeAcrossTenants(final String shortCode) {
        ShortLinkMappingDO data = mappingMapper.selectByShortCodeAcrossTenants(shortCode);

        return Optional.ofNullable(data)
                .map(this::toDomain);
    }

    private ShortLinkMapping toDomain(ShortLinkMappingDO shortLinkMappingDO) {
        final var mapping = new ShortLinkMapping();

        mapping.setId(shortLinkMappingDO.getId());
        mapping.setTenantId(shortLinkMappingDO.getTenantId());
        mapping.setShortLinkId(shortLinkMappingDO.getShortLinkId());
        mapping.setShortCode(shortLinkMappingDO.getShortCode());
        mapping.setCreatedAt(shortLinkMappingDO.getCreatedAt());

        return mapping;
    }
}
