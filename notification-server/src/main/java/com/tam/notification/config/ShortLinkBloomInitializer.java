package com.tam.notification.config;

import com.tam.notification.domain.shortlink.ShortLinkMappingRepository;
import com.tam.notification.domain.shortlink.ShortLinkProtection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动初始化器初始化短链布隆过滤器
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE) // 优先级最高
public class ShortLinkBloomInitializer implements ApplicationRunner {

    private final ShortLinkMappingRepository mappingRepository;
    private final ShortLinkProtection shortLinkProtection;

    @Override
    public void run(final ApplicationArguments args) throws Exception {
        try {
            List<String> shortCodes = mappingRepository.findAllShortCodesAcrossTenants();
            shortLinkProtection.rebuildBloom(shortCodes);
            log.info("initialize short-link success, count={}", shortCodes.size());
        } catch (RuntimeException e) {
            // 初始化失败不能阻止应用启动，ready标志不存在时，查询会自动放行数据库
            log.error("initialize short-link bloom filter failed", e);
        }
    }
}
