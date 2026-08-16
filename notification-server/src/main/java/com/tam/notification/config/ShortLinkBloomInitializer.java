package com.tam.notification.config;

import com.tam.notification.domain.shortlink.ShortLinkMappingRepository;
import com.tam.notification.domain.shortlink.ShortLinkProtection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 启动预热，并在运行期间检测 UTC 时间片轮换。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE) // 优先级最高
public class ShortLinkBloomInitializer implements ApplicationRunner {

    private final ShortLinkMappingRepository mappingRepository;
    private final ShortLinkProtection shortLinkProtection;
    private final AtomicBoolean rebuilding = new AtomicBoolean(false);

    public ShortLinkBloomInitializer(
            ShortLinkMappingRepository mappingRepository,
            ShortLinkProtection shortLinkProtection
    ) {
        this.mappingRepository = mappingRepository;
        this.shortLinkProtection = shortLinkProtection;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureCurrentSliceReady();
    }

    @Scheduled(fixedDelayString = "${notification.shortlink.bloom.rebuild-check-interval-ms:60000}")
    public void ensureCurrentSliceReady() {
        if (shortLinkProtection.isBloomReady()) {
            return;
        }

        // 防止本机的启动线程与调度线程重复扫描数据库。
        if (!rebuilding.compareAndSet(false, true)) {
            return;
        }

        try {
            // CAS 等待期间，另一个执行者可能已经完成。
            if (shortLinkProtection.isBloomReady()) {
                return;
            }
            if (!shortLinkProtection.beginBloomRebuild()) {
                return;
            }

            List<String> shortCodes = mappingRepository.findAllActiveShortCodesAcrossTenants();
            shortLinkProtection.completeBloomRebuild(shortCodes);

            log.info("short-link bloom current slice initialized, count={}", shortCodes.size());
        } catch (RuntimeException exception) {
            // ready 缺失时查询自动放行 MySQL，不能阻止应用启动。
            log.error("initialize current bloom slice failed", exception);
        } finally {
            rebuilding.set(false);
        }
    }
}
