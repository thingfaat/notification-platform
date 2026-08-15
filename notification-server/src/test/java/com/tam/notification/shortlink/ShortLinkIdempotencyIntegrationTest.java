package com.tam.notification.shortlink;

import com.tam.notification.ServerApplication;
import com.tam.notification.common.exception.BusinessException;
import com.tam.notification.common.tenant.TenantContext;
import com.tam.notification.config.ShortLinkBloomInitializer;
import com.tam.notification.domain.application.Application;
import com.tam.notification.domain.application.ApplicationRepository;
import com.tam.notification.domain.shortlink.ShortCodeGenerator;
import com.tam.notification.outbox.OutboxScheduler;
import com.tam.notification.shortlink.dto.CreateShortLinkCommand;
import com.tam.notification.shortlink.dto.CreatedShortLink;
import com.tam.notification.shortlink.service.ShortLinkService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@Testcontainers
@SpringBootTest(
        classes = ServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                // Redis 故意指向不可用端口，验证创建正确性不依赖 Redis。
                "spring.data.redis.host=127.0.0.1",
                "spring.data.redis.port=1",
                "spring.data.redis.timeout=50ms",
                // 本测试不发送 MQ，只需让配置绑定完整。
                "rocketmq.name-server=127.0.0.1:9876"
        }
)
public class ShortLinkIdempotencyIntegrationTest {

    private static final long TENANT_ID = 10001L;
    private static final long APPLICATION_ID = 20001L;

    @Container
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.4")
                    .withDatabaseName("notification_platform")
                    .withUsername("notification")
                    .withPassword("notification123");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Autowired
    private ShortLinkService shortLinkService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /*
     * 替换生产随机生成器，使冲突实验完全可重复。
     * 只替换 @Primary Bean，Snowflake 实验 Bean 不参与创建链路。
     */
    @MockitoBean(name = "base62RandomShortCodeGenerator")
    private ShortCodeGenerator shortCodeGenerator;

    @MockitoBean
    private ApplicationRepository applicationRepository;

    /* 禁止与本实验无关的启动重建和定时 Outbox 任务干扰测试。 */
    @MockitoBean
    private ShortLinkBloomInitializer shortLinkBloomInitializer;

    @MockitoBean
    private OutboxScheduler outboxScheduler;

    @BeforeEach
    void setUp() {
        reset(shortCodeGenerator, applicationRepository);

        Application application = new Application();
        application.setId(APPLICATION_ID);
        application.setTenantId(TENANT_ID);
        application.setStatus(1);

        when(applicationRepository.findById(anyLong()))
                .thenReturn(java.util.Optional.of(application));

        // 外键尚未建立，但仍按依赖顺序清理，保持测试意图清晰。
        jdbcTemplate.update("DELETE FROM short_link_mapping");
        jdbcTemplate.update("DELETE FROM short_link");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void shouldCreateOnlyOneBusinessRecordForOneHundredConcurrentRequests() throws Exception {
        int concurrency = 100;
        AtomicInteger sequence = new AtomicInteger();

        when(shortCodeGenerator.generate()).thenAnswer(invocation ->
                String.format("D17%05d", sequence.incrementAndGet())
        );

        LocalDateTime expireAt = LocalDateTime
                .now()
                .plusDays(7)
                .withNano(123_000_000);

        CreateShortLinkCommand command =
                CreateShortLinkCommand.management(
                        APPLICATION_ID,
                        "req-concurrent-001",
                        "https://example.com/orders/100",
                        expireAt
                );

        ExecutorService executor =
                Executors.newFixedThreadPool(concurrency);
        CountDownLatch startGate = new CountDownLatch(1);

        try {
            List<Future<CreatedShortLink>> futures = new ArrayList<>();

            for (int index = 0; index < concurrency; index++) {
                futures.add(executor.submit(() -> {
                    TenantContext.setTenantId(TENANT_ID);
                    try {
                        startGate.await();
                        return shortLinkService.create(command);
                    } finally {
                        TenantContext.clear();
                    }
                }));
            }

            startGate.countDown();

            Set<Long> shortLinkIds = new HashSet<>();
            Set<String> shortCodes = new HashSet<>();

            for (Future<CreatedShortLink> future : futures) {
                CreatedShortLink result = future.get(30, TimeUnit.SECONDS);
                shortLinkIds.add(result.id());
                shortCodes.add(result.shortCode());
            }

            assertEquals(1, shortLinkIds.size());
            assertEquals(1, shortCodes.size());
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, countShortLinks("req-concurrent-001"));
        assertEquals(1, countMappings());
    }

    @Test
    void shouldRetryWhenDatabaseRejectsCollidingShortCode() {
        insertOccupiedShortCode("COLLIDE1");

        when(shortCodeGenerator.generate())
                .thenReturn("COLLIDE1", "UNIQUE01");

        TenantContext.setTenantId(TENANT_ID);

        CreatedShortLink result = shortLinkService.create(
                CreateShortLinkCommand.management(
                        APPLICATION_ID,
                        "req-code-collision",
                        "https://example.com/orders/101",
                        LocalDateTime.now().plusDays(7)
                )
        );

        assertEquals("UNIQUE01", result.shortCode());
        assertEquals(1, countShortLinks("req-code-collision"));
    }

    @Test
    void shouldRollbackBusinessRecordWhenAllShortCodesCollide() {
        insertOccupiedShortCode("COLLIDE1");
        when(shortCodeGenerator.generate()).thenReturn("COLLIDE1");

        TenantContext.setTenantId(TENANT_ID);

        assertThrows(
                BusinessException.class,
                () -> shortLinkService.create(
                        CreateShortLinkCommand.management(
                                APPLICATION_ID,
                                "req-rollback",
                                "https://example.com/orders/102",
                                LocalDateTime.now().plusDays(7)
                        )
                )
        );

        /*
         * 如果 @Transactional 生效，最先插入的 short_link 会随异常回滚。
         * 不允许留下只有幂等键、没有 shortCode 的半成品。
         */
        assertEquals(0, countShortLinks("req-rollback"));
    }

    @Test
    void shouldRejectSameIdempotencyKeyWithDifferentPayload() {
        when(shortCodeGenerator.generate()).thenReturn("PAYLOAD1");
        TenantContext.setTenantId(TENANT_ID);

        LocalDateTime expireAt = LocalDateTime.now().plusDays(7);

        shortLinkService.create(
                CreateShortLinkCommand.management(
                        APPLICATION_ID,
                        "req-payload-conflict",
                        "https://example.com/orders/200",
                        expireAt
                )
        );

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> shortLinkService.create(
                        CreateShortLinkCommand.management(
                                APPLICATION_ID,
                                "req-payload-conflict",
                                "https://example.com/orders/201",
                                expireAt
                        )
                )
        );

        assertEquals(
                "idempotencyKey已被不同请求使用",
                exception.getMessage()
        );
        assertEquals(1, countShortLinks("req-payload-conflict"));
        assertEquals(1, countMappings());
    }

    @Test
    void shouldCommitToMySqlWhenRedisIsUnavailable() {
        when(shortCodeGenerator.generate()).thenReturn("REDIS001");
        TenantContext.setTenantId(TENANT_ID);

        CreatedShortLink result = shortLinkService.create(
                CreateShortLinkCommand.management(
                        APPLICATION_ID,
                        "req-redis-down",
                        "https://example.com/orders/103",
                        LocalDateTime.now().plusDays(7)
                )
        );

        /*
         * Redis 端口被配置为 1，AFTER_COMMIT 更新会失败。
         * RedisShortLinkProtection 会记录日志并 fail-open，MySQL 结果仍然成立。
         */
        assertEquals("REDIS001", result.shortCode());
        assertEquals(1, countShortLinks("req-redis-down"));
        assertEquals(1, countMappings());
    }

    private int countShortLinks(String idempotencyKey) {
        Integer count = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM short_link
                        WHERE tenant_id = ?
                          AND application_id = ?
                          AND business_type = 'MANAGEMENT'
                          AND idempotency_key = ?
                        """,
                Integer.class,
                TENANT_ID,
                APPLICATION_ID,
                idempotencyKey
        );
        return count == null ? 0 : count;
    }

    private int countMappings() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM short_link_mapping",
                Integer.class
        );
        return count == null ? 0 : count;
    }

    private void insertOccupiedShortCode(String shortCode) {
        jdbcTemplate.update(
                """
                        INSERT INTO short_link_mapping
                            (id, tenant_id, short_link_id, short_code)
                        VALUES (?, ?, ?, ?)
                        """,
                90001L,
                TENANT_ID,
                99999L,
                shortCode
        );
    }
}
