package com.tam.notification.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tam.notification.common.tenant.TenantContext;
import com.tam.notification.domain.enums.ShortLinkStatus;
import com.tam.notification.domain.shortlink.ShortLink;
import com.tam.notification.domain.shortlink.ShortLinkBusinessType;
import com.tam.notification.domain.shortlink.ShortLinkRepository;
import com.tam.notification.persistence.entity.ShortLinkDO;
import com.tam.notification.persistence.mapper.ShortLinkMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ShortLinkRepositoryImpl implements ShortLinkRepository {

    private final ShortLinkMapper shortLinkMapper;

    @Override
    public boolean trySave(final ShortLink shortLink) {

        ShortLinkDO data = toDO(shortLink);

        /**
         * 自定义业务幂等插入需要执行sql前就得到tenantId和id。
         * 不依赖“先查再插”，直接让数据库唯一索引裁决并发 winner
         */
        data.setId(IdWorker.getId());
        data.setTenantId(TenantContext.getTenantId());

        try {
            shortLinkMapper.insert(data);

            shortLink.setId(data.getId());
            shortLink.setTenantId(data.getTenantId());
            shortLink.setVersion(data.getVersion());

            return true;
        } catch (DuplicateKeyException exception) {
            /**
             * 唯一键冲突表示当前请求没有赢得插入竞争，
             * Service随后会按业务幂等键查询 winner，并校验请求荷载
             *
             * 这里只捕获 DuplicateKeyException，连接失败、sql语法错误、字段超长等异常必须继续抛出，不能伪装成幂等命中
             */
            return false;
        }
    }

    @Override
    public Optional<ShortLink> findById(final Long id) {
        final var data = shortLinkMapper.selectById(id);

        return Optional.ofNullable(data)
                .map(this::toDomain);
    }

    @Override
    public Optional<ShortLink> findByIdempotencyKey(final Long applicationId, final ShortLinkBusinessType businessType, final String idempotencyKey) {

        final var data = shortLinkMapper.selectOne(
                Wrappers.<ShortLinkDO>lambdaQuery()
                        .eq(ShortLinkDO::getApplicationId, applicationId)
                        .eq(ShortLinkDO::getBusinessType, businessType)
                        .eq(ShortLinkDO::getIdempotencyKey, idempotencyKey)
        );
        return Optional.ofNullable(data)
                .map(this::toDomain);
    }

    private ShortLinkDO toDO(ShortLink shortLink) {
        ShortLinkDO data = new ShortLinkDO();

        data.setId(shortLink.getId());
        data.setTenantId(shortLink.getTenantId());
        data.setApplicationId(shortLink.getApplicationId());
        data.setIdempotencyKey(shortLink.getIdempotencyKey());
        data.setOriginalUrl(shortLink.getOriginalUrl());
        data.setExpireAt(shortLink.getExpireAt());
        data.setVersion(shortLink.getVersion());

        if (shortLink.getBusinessType() != null) {
            data.setBusinessType(shortLink.getBusinessType().name());
        }

        if (shortLink.getStatus() != null) {
            data.setStatus(shortLink.getStatus().name());
        }

        return data;
    }

    private ShortLink toDomain(ShortLinkDO shortLinkDO) {
        final var shortLink = new ShortLink();

        shortLink.setId(shortLinkDO.getId());
        shortLink.setTenantId(shortLinkDO.getTenantId());
        shortLink.setApplicationId(shortLinkDO.getApplicationId());
        shortLink.setIdempotencyKey(shortLinkDO.getIdempotencyKey());
        shortLink.setOriginalUrl(shortLinkDO.getOriginalUrl());
        shortLink.setExpireAt(shortLinkDO.getExpireAt());
        shortLink.setCreatedAt(shortLinkDO.getCreatedAt());
        shortLink.setUpdatedAt(shortLinkDO.getUpdatedAt());

        if (shortLinkDO.getBusinessType() != null) {
            shortLink.setBusinessType(
                    ShortLinkBusinessType.valueOf(shortLinkDO.getBusinessType())
            );
        }

        if (shortLinkDO.getStatus() != null) {
            shortLink.setStatus(ShortLinkStatus.valueOf(shortLinkDO.getStatus()));
        }

        return shortLink;
    }
}
