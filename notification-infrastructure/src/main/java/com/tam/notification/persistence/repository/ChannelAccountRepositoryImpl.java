package com.tam.notification.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tam.notification.persistence.mapper.ChannelAccountMapper;
import com.tam.notification.domain.channel.ChannelAccount;
import com.tam.notification.persistence.entity.ChannelAccountDO;
import com.tam.notification.domain.channel.ChannelAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ChannelAccountRepositoryImpl implements ChannelAccountRepository {

    private final ChannelAccountMapper channelAccountMapper;

    @Override
    public ChannelAccount save(final ChannelAccount channelAccount) {
        ChannelAccountDO data = toDO(channelAccount);
        channelAccountMapper.insert(data);
        channelAccount.setId(data.getId());
        return channelAccount;
    }

    @Override
    public Optional<ChannelAccount> findById(final Long id) {
        ChannelAccountDO data = channelAccountMapper.selectById(id);
        return Optional.ofNullable(data).map(this::toDomain);
    }

    @Override
    public Optional<ChannelAccount> findByTenantIdAndApplicationIdAndAccountCode(
            final Long tenantId, final Long applicationId, final String accountCode) {
        ChannelAccountDO data = channelAccountMapper.selectOne(
                Wrappers.<ChannelAccountDO>lambdaQuery()
                        .eq(ChannelAccountDO::getTenantId, tenantId)
                        .eq(ChannelAccountDO::getApplicationId, applicationId)
                        .eq(ChannelAccountDO::getAccountCode, accountCode));
        return Optional.ofNullable(data).map(this::toDomain);
    }

    @Override
    public void update(final ChannelAccount channelAccount) {
        channelAccountMapper.updateById(toDO(channelAccount));
    }

    @Override
    public void deleteById(final Long id) {
        channelAccountMapper.deleteById(id);
    }

    private ChannelAccountDO toDO(final ChannelAccount channelAccount) {
        ChannelAccountDO data = new ChannelAccountDO();
        data.setId(channelAccount.getId());
        data.setTenantId(channelAccount.getTenantId());
        data.setApplicationId(channelAccount.getApplicationId());
        data.setAccountCode(channelAccount.getAccountCode());
        data.setAccountName(channelAccount.getAccountName());
        data.setChannelType(channelAccount.getChannelType());
        data.setProvider(channelAccount.getProvider());
        data.setConfigJson(channelAccount.getConfigJson());
        data.setStatus(channelAccount.getStatus());
        data.setVersion(channelAccount.getVersion());
        data.setCreatedAt(channelAccount.getCreatedAt());
        data.setUpdatedAt(channelAccount.getUpdatedAt());
        return data;
    }

    private ChannelAccount toDomain(final ChannelAccountDO data) {
        ChannelAccount channelAccount = new ChannelAccount();
        channelAccount.setId(data.getId());
        channelAccount.setTenantId(data.getTenantId());
        channelAccount.setApplicationId(data.getApplicationId());
        channelAccount.setAccountCode(data.getAccountCode());
        channelAccount.setAccountName(data.getAccountName());
        channelAccount.setChannelType(data.getChannelType());
        channelAccount.setProvider(data.getProvider());
        channelAccount.setConfigJson(data.getConfigJson());
        channelAccount.setStatus(data.getStatus());
        channelAccount.setVersion(data.getVersion());
        channelAccount.setCreatedAt(data.getCreatedAt());
        channelAccount.setUpdatedAt(data.getUpdatedAt());
        return channelAccount;
    }
}
