package com.tam.notification.shortlink.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.tam.notification.common.tenant.TenantContext;
import com.tam.notification.shortlink.domain.ShortLinkMapping;
import com.tam.notification.shortlink.persistence.entity.ShortLinkMappingDO;
import com.tam.notification.shortlink.persistence.mapper.ShortLinkMappingMapper;
import com.tam.notification.shortlink.repository.ShortLinkMappingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

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
}
