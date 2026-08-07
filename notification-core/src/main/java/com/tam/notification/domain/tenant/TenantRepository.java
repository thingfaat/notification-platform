package com.tam.notification.domain.tenant;

import java.util.Optional;

public interface TenantRepository {
    Tenant save(Tenant tenant);

    Optional<Tenant> findById(Long id);

    Optional<Tenant> findByCode(String code);

    void update(Tenant tenant);

    void deleteById(Long id);
}
