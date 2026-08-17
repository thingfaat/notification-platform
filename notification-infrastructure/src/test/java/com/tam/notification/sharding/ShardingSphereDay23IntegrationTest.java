package com.tam.notification.sharding;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * day23必做集成实验
 * 使用两个真实 mysql容器，每个容器4张发送记录表
 * 测试同时检查逻辑结果和物理表分布，避免”配置能加载就算成功“
 */
@Testcontainers
public class ShardingSphereDay23IntegrationTest {

    private static final long TENANT_CRUD = 23_001L;
    private static final long TENANT_QUERY = 23_002L;
    private static final long TENANT_TRANSACTION = 23_003L;
    private static final long TENANT_BINDING = 23_004L;
    private static final long TENANT_BROKEN = 23_005L;

    private static final DockerImageName MYSQL_IMAGE = DockerImageName.parse("mysql:8.0");

    @Container
    private static final MySQLContainer<?> DS_0 = new MySQLContainer<>(MYSQL_IMAGE)
            .withDatabaseName("notification_shard_0")
            .withUsername("notification")
            .withPassword("notification123");

    @Container
    private static final MySQLContainer<?> DS_1 = new MySQLContainer<>(MYSQL_IMAGE)
            .withDatabaseName("notification_shard_1")
            .withUsername("notification")
            .withPassword("notification123");

    private static DataSource shardingDataSource;
    private static JdbcTemplate jdbc;
    private static JdbcTemplate brokenJdbc;

    @BeforeAll
    static void setUpShardingSphere() throws Exception {
        // Testcontainers 已经为两个容器分配动态端口。
        // 把真实连接信息提供给 YAML 的 $${...} 占位符。
        setDataSourceProperties("day23.ds0", DS_0);
        setDataSourceProperties("day23.ds1", DS_1);

        // actualDataNodes 不建表，必须先初始化两个真实 MySQL。
        Day23PhysicalSchema.create(DS_0);
        Day23PhysicalSchema.create(DS_1);

        Class.forName(
                "org.apache.shardingsphere.driver.ShardingSphereDriver"
        );

        shardingDataSource = shardingDataSource(
                "day23/sharding-day23.yaml"
        );
        jdbc = new JdbcTemplate(shardingDataSource);

        // 错误配置使用相同物理库，但拥有独立逻辑 DataSource。
        brokenJdbc = new JdbcTemplate(shardingDataSource(
                "day23/sharding-day23-broken.yaml"
        ));
    }

    @BeforeEach
    void cleanPhysicalTables() throws SQLException {
        Day23PhysicalSchema.clear(DS_0);
        Day23PhysicalSchema.clear(DS_1);
    }

    @AfterAll
    static void clearDynamicProperties() {
        for (String prefix : List.of("day23.ds0", "day23.ds1")) {
            System.clearProperty(prefix + ".jdbc-url");
            System.clearProperty(prefix + ".username");
            System.clearProperty(prefix + ".password");
        }
    }

    @Test
    void crudShouldUseOneShardAndGenerateGlobalId() throws SQLException {
        long firstMessageId = 12L;
        long secondMessageId = 13L;

        // INSERT 故意不提供 id，验证 ShardingSphere keyGenerateStrategy。
        insertSendRecord(jdbc, TENANT_CRUD, firstMessageId, 1, "PROCESSING");
        insertSendRecord(jdbc, TENANT_CRUD, secondMessageId, 1, "PROCESSING");

        Long firstId = findRecordId(TENANT_CRUD, firstMessageId);
        Long secondId = findRecordId(TENANT_CRUD, secondMessageId);

        assertNotNull(firstId);
        assertNotNull(secondId);
        assertTrue(firstId > 0);
        assertTrue(secondId > 0);
        assertNotEquals(firstId, secondId, "逻辑表范围内 ID 必须唯一");

        // messageId=12: (12 >> 2) & 1 = 1，12 & 3 = 0。
        // messageId=13: 同库、表后缀为 1。
        assertEquals(
                Set.of(
                        new Day23PhysicalSchema.PhysicalNode(1, 0),
                        new Day23PhysicalSchema.PhysicalNode(1, 1)
                ),
                Day23PhysicalSchema.activeSendRecordNodes(
                        DS_0,
                        DS_1,
                        TENANT_CRUD
                )
        );

        Integer readCount = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM notify_send_record
                WHERE tenant_id = ?
                  AND message_id = ?
                """, Integer.class, TENANT_CRUD, firstMessageId);
        assertEquals(1, readCount);

        // UPDATE 同时带 id、message_id、tenant_id：路由和隔离条件都完整。
        int updated = jdbc.update("""
                UPDATE notify_send_record
                SET send_status = 'SUCCESS',
                    provider_message_id = 'provider-day23',
                    finished_at = ?
                WHERE id = ?
                  AND message_id = ?
                  AND tenant_id = ?
                  AND send_status = 'PROCESSING'
                """, Timestamp.valueOf(LocalDateTime.now()),
                firstId, firstMessageId, TENANT_CRUD);
        assertEquals(1, updated);

        String status = jdbc.queryForObject("""
                SELECT send_status
                FROM notify_send_record
                WHERE tenant_id = ?
                  AND message_id = ?
                """, String.class, TENANT_CRUD, firstMessageId);
        assertEquals("SUCCESS", status);

        int deleted = jdbc.update("""
                DELETE FROM notify_send_record
                WHERE id = ?
                  AND message_id = ?
                  AND tenant_id = ?
                """, secondId, secondMessageId, TENANT_CRUD);
        assertEquals(1, deleted);
        assertNull(findRecordId(TENANT_CRUD, secondMessageId));
    }

    @Test
    void rangePaginationAndAggregationShouldMergeCorrectly()
            throws SQLException {
        for (long messageId = 100; messageId < 116; messageId++) {
            String status = messageId % 2 == 0 ? "SUCCESS" : "FAILED";
            insertSendRecord(
                    jdbc,
                    TENANT_QUERY,
                    messageId,
                    1,
                    status
            );
        }

        // 100..107 已经覆盖 2×4 的全部节点。
        assertEquals(
                8,
                Day23PhysicalSchema.activeSendRecordNodes(
                        DS_0,
                        DS_1,
                        TENANT_QUERY
                ).size()
        );

        Integer total = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM notify_send_record
                WHERE tenant_id = ?
                  AND message_id BETWEEN ? AND ?
                """, Integer.class, TENANT_QUERY, 100L, 115L);
        assertEquals(16, total);

        List<Long> page = jdbc.queryForList("""
                SELECT message_id
                FROM notify_send_record
                WHERE tenant_id = ?
                  AND message_id BETWEEN ? AND ?
                ORDER BY message_id
                LIMIT 5 OFFSET 5
                """, Long.class, TENANT_QUERY, 100L, 115L);
        assertEquals(List.of(105L, 106L, 107L, 108L, 109L), page);

        List<Map<String, Object>> aggregation = jdbc.queryForList("""
                SELECT send_status, COUNT(*) AS amount
                FROM notify_send_record
                WHERE tenant_id = ?
                GROUP BY send_status
                ORDER BY send_status
                """, TENANT_QUERY);

        assertEquals(2, aggregation.size());
        assertEquals(8L, amountOf(aggregation, "FAILED"));
        assertEquals(8L, amountOf(aggregation, "SUCCESS"));
    }

    @Test
    void tenantConditionMustStillBePartOfLogicalSql() {
        long sharedMessageId = 40L;
        long anotherTenant = TENANT_QUERY + 100;

        // 唯一索引包含 tenant_id，因此两个租户可以拥有相同 messageId/attemptNo。
        insertSendRecord(
                jdbc,
                TENANT_QUERY,
                sharedMessageId,
                1,
                "SUCCESS"
        );
        insertSendRecord(
                jdbc,
                anotherTenant,
                sharedMessageId,
                1,
                "SUCCESS"
        );

        Integer isolatedCount = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM notify_send_record
                WHERE message_id = ?
                  AND tenant_id = ?
                """, Integer.class, sharedMessageId, TENANT_QUERY);
        assertEquals(1, isolatedCount);

        Integer unsafeCount = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM notify_send_record
                WHERE message_id = ?
                """, Integer.class, sharedMessageId);
        assertEquals(
                2,
                unsafeCount,
                "ShardingSphere 负责路由，不会自动替代 MyBatis 租户拦截器"
        );
    }

    @Test
    void bindingJoinShouldStayOnOneNode() throws SQLException {
        long messageId = 53L;

        jdbc.update("""
                INSERT INTO notify_message
                    (id, tenant_id, message_no, message_status, created_at)
                VALUES (?, ?, ?, 'SENT', ?)
                """,
                messageId,
                TENANT_BINDING,
                "MSG-DAY23-" + messageId,
                Timestamp.valueOf(LocalDateTime.now())
        );
        insertSendRecord(
                jdbc,
                TENANT_BINDING,
                messageId,
                1,
                "SUCCESS"
        );

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT m.id AS message_id,
                       r.id AS send_record_id,
                       r.send_status
                FROM notify_message m
                JOIN notify_send_record r
                  ON m.id = r.message_id
                WHERE m.id = ?
                  AND m.tenant_id = ?
                  AND r.tenant_id = ?
                """, messageId, TENANT_BINDING, TENANT_BINDING);

        assertEquals(1, rows.size());
        assertEquals("SUCCESS", rows.get(0).get("send_status"));

        // 53: (53 >> 2) & 1 = 1，53 & 3 = 1。
        assertEquals(
                Set.of(new Day23PhysicalSchema.PhysicalNode(1, 1)),
                Day23PhysicalSchema.activeSendRecordNodes(
                        DS_0,
                        DS_1,
                        TENANT_BINDING
                )
        );
    }

    @Test
    void broadcastInsertShouldReachBothDatabases() throws SQLException {
        int inserted = jdbc.update("""
                INSERT INTO sys_tenant
                    (id, tenant_code, tenant_name, status, deleted, version)
                VALUES (?, ?, ?, 1, 0, 0)
                """, 23_900L, "day23", "Day23 Tenant");

        // JDBC 返回值可能反映多节点执行总数，不用它判断副本完整性。
        assertTrue(inserted >= 1);
        assertEquals(1, Day23PhysicalSchema.countTenants(DS_0));
        assertEquals(1, Day23PhysicalSchema.countTenants(DS_1));
    }

    @Test
    void businessRollbackShouldRollbackBothDatabases() throws Exception {
        try (Connection connection = shardingDataSource.getConnection()) {
            connection.setAutoCommit(false);

            try {
                // 200 路由 ds_0，204 路由 ds_1。
                insertSendRecord(
                        connection,
                        TENANT_TRANSACTION,
                        200L,
                        1,
                        "PROCESSING"
                );
                insertSendRecord(
                        connection,
                        TENANT_TRANSACTION,
                        204L,
                        1,
                        "PROCESSING"
                );

                // 模拟业务校验失败，不进入 commit。
                throw new IllegalStateException("day23 rollback probe");
            } catch (IllegalStateException expected) {
                connection.rollback();
            }
        }

        assertTrue(
                Day23PhysicalSchema.activeSendRecordNodes(
                        DS_0,
                        DS_1,
                        TENANT_TRANSACTION
                ).isEmpty()
        );
    }

    @Test
    void relatedModuloConfigurationShouldReachOnlyHalfNodes()
            throws SQLException {
        for (long messageId = 0; messageId < 8; messageId++) {
            insertSendRecord(
                    brokenJdbc,
                    TENANT_BROKEN,
                    messageId,
                    1,
                    "SUCCESS"
            );
        }

        Set<Day23PhysicalSchema.PhysicalNode> activeNodes =
                Day23PhysicalSchema.activeSendRecordNodes(
                        DS_0,
                        DS_1,
                        TENANT_BROKEN
                );

        System.out.println("broken active nodes = " + activeNodes);
        assertEquals(
                4,
                activeNodes.size(),
                "同一 messageId 分别 %2、%4，只能命中 4/8 个节点"
        );
    }

    private static void insertSendRecord(
            JdbcTemplate target,
            long tenantId,
            long messageId,
            int attemptNo,
            String status
    ) {
        target.update("""
                INSERT INTO notify_send_record
                    (tenant_id, message_id, event_id, attempt_no,
                     channel_type, idempotency_key, send_status, started_at)
                VALUES (?, ?, ?, ?, 'EMAIL', ?, ?, ?)
                """,
                tenantId,
                messageId,
                "event-" + tenantId + "-" + messageId + "-" + attemptNo,
                attemptNo,
                "idem-" + tenantId + "-" + messageId + "-" + attemptNo,
                status,
                Timestamp.valueOf(LocalDateTime.now())
        );
    }

    private static void insertSendRecord(
            Connection connection,
            long tenantId,
            long messageId,
            int attemptNo,
            String status
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO notify_send_record
                    (tenant_id, message_id, event_id, attempt_no,
                     channel_type, idempotency_key, send_status, started_at)
                VALUES (?, ?, ?, ?, 'EMAIL', ?, ?, ?)
                """)) {
            statement.setLong(1, tenantId);
            statement.setLong(2, messageId);
            statement.setString(3, "tx-event-" + messageId);
            statement.setInt(4, attemptNo);
            statement.setString(5, "tx-idem-" + tenantId + "-" + messageId);
            statement.setString(6, status);
            statement.setTimestamp(7, Timestamp.valueOf(LocalDateTime.now()));
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static Long findRecordId(long tenantId, long messageId) {
        List<Long> ids = jdbc.queryForList("""
                SELECT id
                FROM notify_send_record
                WHERE tenant_id = ?
                  AND message_id = ?
                """, Long.class, tenantId, messageId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private static long amountOf(
            List<Map<String, Object>> rows,
            String status
    ) {
        return rows.stream()
                .filter(row -> status.equals(row.get("send_status")))
                .map(row -> ((Number) row.get("amount")).longValue())
                .findFirst()
                .orElseThrow();
    }

    private static void setDataSourceProperties(
            String prefix,
            MySQLContainer<?> container
    ) {
        System.setProperty(prefix + ".jdbc-url", container.getJdbcUrl());
        System.setProperty(prefix + ".username", container.getUsername());
        System.setProperty(prefix + ".password", container.getPassword());
    }

    private static DataSource shardingDataSource(String yamlPath) {
        DriverManagerDataSource result = new DriverManagerDataSource();
        result.setDriverClassName(
                "org.apache.shardingsphere.driver.ShardingSphereDriver"
        );
        result.setUrl(
                "jdbc:shardingsphere:classpath:"
                        + yamlPath
                        + "?placeholder-type=system_props"
        );
        return result;
    }
}
