package com.tam.notification.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tam.notification.persistence.mapper.ApplicationMapper;
import com.tam.notification.domain.application.Application;
import com.tam.notification.persistence.entity.ApplicationDO;
import com.tam.notification.domain.application.ApplicationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ApplicationRepositoryImpl implements ApplicationRepository {

    private final ApplicationMapper applicationMapper;

    @Override
    public Application save(final Application application) {
        ApplicationDO data = toDO(application);
        applicationMapper.insert(data);
        application.setId(data.getId());
        return application;
    }

    @Override
    public Optional<Application> findById(final Long id) {
        ApplicationDO data = applicationMapper.selectById(id);
        return Optional.ofNullable(data).map(this::toDomain);
    }

    @Override
    public Optional<Application> findByTenantIdAndAppCode(final Long tenantId, final String appCode) {
        ApplicationDO data = applicationMapper.selectOne(
                Wrappers.<ApplicationDO>lambdaQuery()
                        .eq(ApplicationDO::getTenantId, tenantId)
                        .eq(ApplicationDO::getAppCode, appCode));
        return Optional.ofNullable(data).map(this::toDomain);
    }

    @Override
    public void update(final Application application) {
        applicationMapper.updateById(toDO(application));
    }

    @Override
    public void deleteById(final Long id) {
        applicationMapper.deleteById(id);
    }

    private ApplicationDO toDO(final Application application) {
        ApplicationDO data = new ApplicationDO();
        data.setId(application.getId());
        data.setTenantId(application.getTenantId());
        data.setAppCode(application.getAppCode());
        data.setAppName(application.getAppName());
        data.setStatus(application.getStatus());
        data.setVersion(application.getVersion());
        data.setCreatedAt(application.getCreatedAt());
        data.setUpdatedAt(application.getUpdatedAt());
        return data;
    }

    private Application toDomain(final ApplicationDO data) {
        Application application = new Application();
        application.setId(data.getId());
        application.setTenantId(data.getTenantId());
        application.setAppCode(data.getAppCode());
        application.setAppName(data.getAppName());
        application.setStatus(data.getStatus());
        application.setVersion(data.getVersion());
        application.setCreatedAt(data.getCreatedAt());
        application.setUpdatedAt(data.getUpdatedAt());
        return application;
    }
}
