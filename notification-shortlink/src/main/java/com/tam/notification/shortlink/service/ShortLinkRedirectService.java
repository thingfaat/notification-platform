package com.tam.notification.shortlink.service;

import com.tam.notification.common.tenant.TenantContext;
import com.tam.notification.domain.enums.ShortLinkStatus;
import com.tam.notification.domain.shortlink.*;
import com.tam.notification.shortlink.dto.ResolvedShortLink;
import com.tam.notification.shortlink.exception.ShortLinkExpiredException;
import com.tam.notification.shortlink.exception.ShortLinkNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ShortLinkRedirectService {

    private static final Pattern SHORT_CODE_PATTERN = Pattern.compile("[0-9a-zA-Z]{8}");

    private static final Duration MAX_CACHE_TTL = Duration.ofMinutes(30);

    private final ShortLinkCache shortLinkCache;

    private final ShortLinkMappingRepository mappingRepository;

    private final ShortLinkRepository shortLinkRepository;

    @Transactional(readOnly = true)
    public ResolvedShortLink resolve(String shortCode) {
        validateShortCode(shortCode);

        LocalDateTime now = LocalDateTime.now();

        Optional<ShortLinkCacheEntry> cached = shortLinkCache.get(shortCode);

        if (cached.isPresent()) {
            return resolveFromCache(shortCode, cached.get(), now);
        }

        final var mapping = mappingRepository.findByShortCodeAcrossTenants(shortCode)
                .orElseThrow(ShortLinkNotFoundException::new);

        ShortLink shortLink = withTenant(
                mapping.getTenantId(),
                () -> shortLinkRepository.findById(mapping.getShortLinkId()).orElseThrow(ShortLinkNotFoundException::new));

        validateAvailable(shortLink, now);

        cache(shortCode, shortLink, now);

        return new ResolvedShortLink(
                shortCode,
                shortLink.getTenantId(),
                shortLink.getId(),
                shortLink.getOriginalUrl()
        );
    }

    private ResolvedShortLink resolveFromCache(
            String shortCode,
            ShortLinkCacheEntry cached,
            LocalDateTime now
    ) {
        if (cached.isExpired(now)) {
            shortLinkCache.evict(shortCode);
            throw new ShortLinkExpiredException();
        }

        return new ResolvedShortLink(
                shortCode,
                cached.tenantId(),
                cached.shortLinkId(),
                cached.originalUrl()
        );
    }

    private void validateAvailable(
            ShortLink shortLink,
            LocalDateTime now
    ) {
        if (shortLink.isExpired(now)) {
            throw new ShortLinkExpiredException();
        }

        if (shortLink.getStatus() != ShortLinkStatus.ACTIVE) {
            throw new ShortLinkNotFoundException();
        }
    }

    private void cache(
            String shortCode,
            ShortLink shortLink,
            LocalDateTime now
    ) {
        Duration remaining = Duration.between(
                now,
                shortLink.getExpireAt()
        );

        Duration ttl = remaining.compareTo(MAX_CACHE_TTL) > 0 ? MAX_CACHE_TTL : remaining;

        ShortLinkCacheEntry entry = new ShortLinkCacheEntry(
                shortLink.getTenantId(),
                shortLink.getId(),
                shortLink.getOriginalUrl(),
                shortLink.getExpireAt()
        );

        shortLinkCache.put(
                shortCode,
                entry,
                ttl
        );
    }

    private void validateShortCode(String shortCode) {
        if (shortCode == null
                || !SHORT_CODE_PATTERN.matcher(shortCode).matches()) {
            throw new ShortLinkNotFoundException();
        }
    }

    private <T> T withTenant(
            Long tenantId,
            Supplier<T> action
    ) {
        if (tenantId == null) {
            throw new ShortLinkNotFoundException();
        }

        Long previousTenantId = TenantContext.getTenantId();

        try {
            TenantContext.setTenantId(tenantId);
            return action.get();
        } finally {
            if (previousTenantId == null) {
                TenantContext.clear();
            } else {
                TenantContext.setTenantId(previousTenantId);
            }
        }
    }
}
