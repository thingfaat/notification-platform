package com.tam.notification.shortlink.service;

import com.tam.notification.common.exception.BusinessException;
import com.tam.notification.common.exception.CommonErrorCode;
import com.tam.notification.domain.application.ApplicationRepository;
import com.tam.notification.domain.shortlink.ShortCodeGenerator;
import com.tam.notification.domain.shortlink.ShortLink;
import com.tam.notification.domain.shortlink.ShortLinkMapping;
import com.tam.notification.domain.enums.ShortLinkStatus;
import com.tam.notification.domain.shortlink.ShortLinkMappingRepository;
import com.tam.notification.domain.shortlink.ShortLinkRepository;
import com.tam.notification.shortlink.dto.CreateShortLinkCommand;
import com.tam.notification.shortlink.dto.CreatedShortLink;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShortLinkService {

    // 循环尝试次数
    private static final int MAX_CODE_GENERATE_ATTEMPTS = 5;
    // 最长原始URL长度
    private static final int MAX_ORIGINAL_URL_LENGTH = 2048;

    private final ApplicationRepository applicationRepository;
    private final ShortLinkRepository shortLinkRepository;
    private final ShortLinkMappingRepository mappingRepository;
    private final ShortCodeGenerator shortCodeGenerator;


    @Transactional
    public CreatedShortLink create(CreateShortLinkCommand command) {
        validateCommand(command);

        final var application = applicationRepository.findById(command.applicationId()).orElseThrow(() -> new BusinessException(CommonErrorCode.BUSINESS_ERROR, "应用不存在"));

        if (!Objects.equals(application.getStatus(), 1)) {
            throw new BusinessException(CommonErrorCode.BUSINESS_ERROR, "应用未启用");
        }

        final var shortLink = new ShortLink();
        shortLink.setApplicationId(command.applicationId());
        shortLink.setOriginalUrl(command.originalUrl().trim());
        shortLink.setExpireAt(command.expireAt());
        shortLink.setStatus(ShortLinkStatus.ACTIVE);

        shortLinkRepository.save(shortLink);

        for (int attempt = 1; attempt <= MAX_CODE_GENERATE_ATTEMPTS; attempt++) {
            String shortCode = shortCodeGenerator.generate();

            ShortLinkMapping mapping = new ShortLinkMapping();
            mapping.setShortLinkId(shortLink.getId());
            mapping.setShortCode(shortCode);

            if (mappingRepository.trySave(mapping)) {
                return toResult(shortLink, shortCode);
            }
        }

        throw new BusinessException(CommonErrorCode.BUSINESS_ERROR, "短码生成冲突次数超过上限，请稍后重试");
    }

    private void validateCommand(CreateShortLinkCommand command) {
        if (command == null) {
            throw invalidParameter("短链创建参数不能为空");
        }

        if (command.applicationId() == null) {
            throw invalidParameter("applicationId不能为空");
        }

        String originalUrl = command.originalUrl();

        if (originalUrl == null || originalUrl.isBlank()) {
            throw invalidParameter("原始URL不能为空");
        }

        if (originalUrl.length() > MAX_ORIGINAL_URL_LENGTH) {
            throw invalidParameter("原始URL长度不能超过2048个字符");
        }

        validateOriginalUrl(originalUrl.trim());

        if (command.expireAt() == null) {
            throw invalidParameter("过期时间不能为空");
        }

        if (!command.expireAt().isAfter(LocalDateTime.now())) {
            throw invalidParameter("过期时间必须晚于当前时间");
        }
    }

    private void validateOriginalUrl(String originalUrl) {
        try {
            URI uri = new URI(originalUrl);
            String scheme = uri.getScheme();

            boolean supportedScheme = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);

            if (!supportedScheme || uri.getHost() == null || uri.getHost().isBlank()) {
                throw invalidParameter("原始URL必须是合法的HTTP或HTTPS地址");
            }
        } catch (URISyntaxException exception) {
            throw invalidParameter("原始URL必须是合法的HTTP或HTTPS地址");
        }
    }

    private BusinessException invalidParameter(String message) {
        return new BusinessException(
                CommonErrorCode.INVALID_PARAMETER,
                message
        );
    }

    private CreatedShortLink toResult(
            ShortLink shortLink,
            String shortCode
    ) {
        return new CreatedShortLink(
                shortLink.getId(),
                shortLink.getTenantId(),
                shortLink.getApplicationId(),
                shortCode,
                shortLink.getOriginalUrl(),
                shortLink.getExpireAt(),
                shortLink.getStatus()
        );
    }
}
