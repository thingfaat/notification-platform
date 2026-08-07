package com.tam.notification.service;

import com.tam.notification.common.tenant.TenantContext;
import com.tam.notification.domain.channel.ChannelAccount;
import com.tam.notification.domain.channel.ChannelAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ChannelAccountService {
    private final ChannelAccountRepository channelAccountRepository;

    public ChannelAccount create(Long applicationId, String accountCode, String accountName,
                                  String channelType, String provider, String configJson) {
        Long tenantId = TenantContext.requireTenantId();
        channelAccountRepository.findByTenantIdAndApplicationIdAndAccountCode(tenantId, applicationId, accountCode)
                .ifPresent(existing -> {
                    throw new RuntimeException("Channel account already exists");
                });

        ChannelAccount channelAccount = new ChannelAccount();
        channelAccount.setTenantId(tenantId);
        channelAccount.setApplicationId(applicationId);
        channelAccount.setAccountCode(accountCode);
        channelAccount.setAccountName(accountName);
        channelAccount.setChannelType(channelType);
        channelAccount.setProvider(provider);
        channelAccount.setConfigJson(configJson);
        channelAccount.setStatus(1);
        return channelAccountRepository.save(channelAccount);
    }
}
