package com.tam.notification.shortlink.service;

import com.tam.notification.common.exception.BusinessException;
import com.tam.notification.common.exception.CommonErrorCode;
import com.tam.notification.domain.application.ApplicationRepository;
import com.tam.notification.domain.enums.ShortLinkStatus;
import com.tam.notification.domain.shortlink.*;
import com.tam.notification.shortlink.dto.CreateShortLinkCommand;
import com.tam.notification.shortlink.dto.CreatedShortLink;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
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
    // 最长幂等键长度
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    private final ApplicationRepository applicationRepository;
    private final ShortLinkRepository shortLinkRepository;
    private final ShortLinkMappingRepository mappingRepository;
    private final ShortCodeGenerator shortCodeGenerator;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 创建短链
     * 使用 `READ_COMMITTED` 也不是装饰。MySQL 默认通常是 `REPEATABLE_READ`：如果事务第一次幂等查询已经建立一致性快照，
     * 那么等待 winner 提交后再次执行普通查询，仍可能看不到刚提交的数据。`READ_COMMITTED` 会让第二次查询读取当时已经提交的 winner
     *
     * @param command
     * @return
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CreatedShortLink create(CreateShortLinkCommand command) {
        /**
         * 第一阶段阶段只校验字段形状，不校验“过期时间必须晚于现在”
         * 原因是幂等重放可能发生在原短链过期之后，重放仍应该返回第一次结束，
         * 而不是创建第二条记录
         */
        validateCommand(command);

        String originalUrl = command.originalUrl().trim();
        String idempotencyKey = command.idempotencyKey().trim();
        LocalDateTime expireAt = truncateToMillis(command.expireAt());

        final var existing = shortLinkRepository.findByIdempotencyKey(
                command.applicationId(),
                command.businessType(),
                idempotencyKey
        );

        // 如果幂等键已经存在了，则直接返回
        if (existing.isPresent()) {
            return reuseExisting(existing.get(), originalUrl, expireAt);
        }

        validateApplication(command.applicationId());

        // 只有真正创建新纪录时，才能要求过期时间晚于当前时间
        if (!expireAt.isAfter(LocalDateTime.now())) {
            throw invalidParameter("过期时间必须晚于当前时间");
        }

        final var candidate = new ShortLink();
        candidate.setApplicationId(command.applicationId());
        candidate.setBusinessType(command.businessType());
        candidate.setIdempotencyKey(command.idempotencyKey());
        candidate.setOriginalUrl(command.originalUrl().trim());
        candidate.setExpireAt(expireAt);
        candidate.setStatus(ShortLinkStatus.ACTIVE);

        // 创建新记录，由底层数据库保证唯一键
        final var winner = shortLinkRepository.trySave(candidate);
        if (!winner) {
            /**
             * 当前事务输掉唯一键竞争
             * innodb会等待winner提交或回滚：
             * - winner 提交：trySave返回false，然后可以读取到已有的记录
             * - winner 回滚：当前 insert 可以继续成功，trySave会返回true
             */
            final var concurrentWinner = shortLinkRepository.findByIdempotencyKey(
                    command.applicationId(),
                    command.businessType(),
                    idempotencyKey
            ).orElseThrow(() -> new BusinessException(
                    CommonErrorCode.INTERNAL_ERROR,
                    "幂等唯一键冲突后未找到已有短链"
            ));
            return reuseExisting(concurrentWinner, originalUrl, expireAt);
        }

        String shortCode = allocateShortCode(candidate);

        // 事件由 AFTER_COMMIT 监听器处理，事务回滚时不会污染 bloom
        eventPublisher.publishEvent(new ShortLinkCreatedEvent(shortCode));
        return toResult(candidate, shortCode);
    }

    /**
     * 为已经赢得业务幂等竞争的short_link分配全局唯一shortCode
     *
     * @param shortLink
     * @return
     */
    private String allocateShortCode(ShortLink shortLink) {
        for (int attempt = 1; attempt <= MAX_CODE_GENERATE_ATTEMPTS; attempt++) {
            String shortCode = shortCodeGenerator.generate();

            ShortLinkMapping mapping = new ShortLinkMapping();
            mapping.setShortLinkId(shortLink.getId());
            mapping.setShortCode(shortCode);

            if (mappingRepository.trySave(mapping)) {
                return shortCode;
            }

            log.warn("short code collision, attempt={}, shortLinkId={}, shortCode={}",
                    attempt,
                    shortLink.getId(),
                    shortCode);
        }

        /**
         * 方法处于@Transactional事务中，抛出运行时异常会同时回滚short_link和已执行的所有写操作，
         * 不会留下“有业务幂等记录但没有mapping”的半成品
         */
        throw new BusinessException(
                CommonErrorCode.INTERNAL_ERROR,
                "短码生成冲突次数超过上限，请稍后重试"
        );
    }

    private CreatedShortLink reuseExisting(
            ShortLink existing,
            String requestedUrl,
            LocalDateTime requestedExpireAt
    ) {
        if (!Objects.equals(existing.getOriginalUrl(), requestedUrl)
                || !Objects.equals(truncateToMillis(existing.getExpireAt()), requestedExpireAt)) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "idempotencyKey已被不同请求使用");
        }

        ShortLinkMapping mapping = mappingRepository.findByShortLinkId(existing.getId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.INTERNAL_ERROR, "已有短链缺少短码映射"));

        return toResult(existing, mapping.getShortCode());
    }

    private void validateApplication(Long applicationId) {
        var application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new BusinessException(
                        CommonErrorCode.BUSINESS_ERROR,
                        "应用不存在"
                ));

        if (!Objects.equals(application.getStatus(), 1)) {
            throw new BusinessException(
                    CommonErrorCode.BUSINESS_ERROR,
                    "应用未启用"
            );
        }
    }

    private void validateCommand(CreateShortLinkCommand command) {
        if (command == null) {
            throw invalidParameter("短链创建参数不能为空");
        }
        if (command.applicationId() == null) {
            throw invalidParameter("applicationId不能为空");
        }
        if (command.businessType() == null) {
            throw invalidParameter("businessType不能为空");
        }
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            throw invalidParameter("idempotencyKey不能为空");
        }
        if (command.idempotencyKey().trim().length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw invalidParameter("idempotencyKey长度不能超过128个字符");
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

    /**
     * 将 Java 纳秒精度统一为数据库 DATETIME(3) 的毫秒精度。
     */
    private LocalDateTime truncateToMillis(LocalDateTime value) {
        if (value == null) {
            return null;
        }

        int millisAsNanos =
                (value.getNano() / 1_000_000) * 1_000_000;
        return value.withNano(millisAsNanos);
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
