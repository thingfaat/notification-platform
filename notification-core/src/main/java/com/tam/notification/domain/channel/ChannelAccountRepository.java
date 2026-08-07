package com.tam.notification.domain.channel;

import java.util.Optional;

public interface ChannelAccountRepository {
    ChannelAccount save(ChannelAccount channelAccount);

    Optional<ChannelAccount> findById(Long id);

    Optional<ChannelAccount> findByTenantIdAndApplicationIdAndAccountCode(Long tenantId, Long applicationId, String accountCode);

    void update(ChannelAccount channelAccount);

    void deleteById(Long id);
}
