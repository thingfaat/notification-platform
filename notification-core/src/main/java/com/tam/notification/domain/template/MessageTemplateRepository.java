package com.tam.notification.domain.template;

import java.util.Optional;

public interface MessageTemplateRepository {
    MessageTemplate save(MessageTemplate template);

    Optional<MessageTemplate> findById(Long id);

    Optional<MessageTemplate> findByTenantIdAndApplicationIdAndTemplateCode(Long tenantId, Long applicationId, String templateCode);

    void update(MessageTemplate template);

    void deleteById(Long id);
}
