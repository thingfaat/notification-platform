package com.tam.notification.domain.application;

import java.util.Optional;

public interface ApplicationRepository {
    Application save(Application application);

    Optional<Application> findById(Long id);

    Optional<Application> findByAppCode(String appCode);

    void update(Application application);

    void deleteById(Long id);
}
