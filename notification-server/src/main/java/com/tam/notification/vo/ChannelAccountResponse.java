package com.tam.notification.vo;

import com.tam.notification.domain.channel.ChannelAccount;
import lombok.Data;

@Data
public class ChannelAccountResponse {
    private Long id;
    private Long tenantId;
    private Long applicationId;
    private String accountCode;
    private String accountName;
    private String channelType;
    private String provider;
    private String configJson;
    private Integer status;

    public static ChannelAccountResponse from(final ChannelAccount channelAccount) {
        ChannelAccountResponse response = new ChannelAccountResponse();
        response.setId(channelAccount.getId());
        response.setTenantId(channelAccount.getTenantId());
        response.setApplicationId(channelAccount.getApplicationId());
        response.setAccountCode(channelAccount.getAccountCode());
        response.setAccountName(channelAccount.getAccountName());
        response.setChannelType(channelAccount.getChannelType());
        response.setProvider(channelAccount.getProvider());
        response.setConfigJson(channelAccount.getConfigJson());
        response.setStatus(channelAccount.getStatus());
        return response;
    }
}
