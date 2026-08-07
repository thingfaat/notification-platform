package com.tam.notification.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tam.notification.persistence.mapper.TenantMapper;
import com.tam.notification.domain.tenant.Tenant;
import com.tam.notification.persistence.entity.TenantDO;
import com.tam.notification.domain.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class TenantRepositoryImpl implements TenantRepository {

    private final TenantMapper tenantMapper;

    @Override
    public Tenant save(final Tenant tenant) {
        TenantDO data = toDO(tenant);
        tenantMapper.insert(data);
        tenant.setId(data.getId());
        return tenant;
    }

    @Override
    public Optional<Tenant> findById(final Long id) {
        TenantDO data = tenantMapper.selectById(id);
        return Optional.ofNullable(data).map(this::toDomain);
    }

    @Override
    public Optional<Tenant> findByCode(final String code) {
        TenantDO data = tenantMapper.selectOne(Wrappers.<TenantDO>lambdaQuery().eq(TenantDO::getTenantCode, code));
        return Optional.ofNullable(data).map(this::toDomain);
    }

    @Override
    public void update(final Tenant tenant) {
        tenantMapper.updateById(toDO(tenant));
    }

    @Override
    public void deleteById(final Long id) {
        tenantMapper.deleteById(id);
    }

    private TenantDO toDO(final Tenant tenant) {
        TenantDO data = new TenantDO();
        data.setId(tenant.getId());
        data.setTenantCode(tenant.getTenantCode());
        data.setTenantName(tenant.getTenantName());
        data.setStatus(tenant.getStatus());
        data.setVersion(tenant.getVersion());
        data.setCreatedAt(tenant.getCreatedAt());
        data.setUpdatedAt(tenant.getUpdatedAt());
        return data;
    }

    private Tenant toDomain(final TenantDO data) {
        Tenant tenant = new Tenant();
        tenant.setId(data.getId());
        tenant.setTenantCode(data.getTenantCode());
        tenant.setTenantName(data.getTenantName());
        tenant.setStatus(data.getStatus());
        tenant.setVersion(data.getVersion());
        tenant.setCreatedAt(data.getCreatedAt());
        tenant.setUpdatedAt(data.getUpdatedAt());
        return tenant;
    }
}
