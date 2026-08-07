package com.tam.notification.service;

import com.tam.notification.common.exception.BusinessException;
import com.tam.notification.common.exception.CommonErrorCode;
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
        channelAccountRepository.findByAccountCode(applicationId, accountCode)
                .ifPresent(existing -> {
                    throw new BusinessException(CommonErrorCode.BUSINESS_ERROR, "渠道账号已经存在");
                });

        ChannelAccount channelAccount = new ChannelAccount();
        channelAccount.setApplicationId(applicationId);
        channelAccount.setAccountCode(accountCode);
        channelAccount.setAccountName(accountName);
        channelAccount.setChannelType(channelType);
        channelAccount.setProvider(provider);
        channelAccount.setConfigJson(configJson);
        channelAccount.setStatus(1);
        return channelAccountRepository.save(channelAccount);
    }

    public ChannelAccount get(Long id) {
        return channelAccountRepository.findById(id)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.BUSINESS_ERROR, "渠道账号不存在"));
    }

    public void update(Long id, Long applicationId, String accountCode, String accountName,
                       String channelType, String provider, String configJson) {
        ChannelAccount channelAccount = channelAccountRepository.findById(id)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.BUSINESS_ERROR, "渠道账号不存在"));
        channelAccount.setApplicationId(applicationId);
        channelAccount.setAccountCode(accountCode);
        channelAccount.setAccountName(accountName);
        channelAccount.setChannelType(channelType);
        channelAccount.setProvider(provider);
        channelAccount.setConfigJson(configJson);
        channelAccountRepository.update(channelAccount);
    }

    public void delete(Long id) {
        channelAccountRepository.deleteById(id);
    }
}
