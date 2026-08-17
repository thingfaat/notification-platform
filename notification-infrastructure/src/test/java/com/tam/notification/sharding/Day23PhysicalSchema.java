package com.tam.notification.sharding;

import org.testcontainers.containers.MySQLContainer;

import java.sql.*;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * day23物理库辅助类
 * actualDataNodes 只描述节点，不负责建表；测试必须显式创建真实物理表。
 * 所有sql只作用于Testcontainers，测试结束后容器会被销毁
 */
public final class Day23PhysicalSchema {

    static final int DATABASE_COUNT = 2;
    static final int TABLE_COUNT_PER_DATABASE = 4;

    private Day23PhysicalSchema() {
    }

    static void create(MySQLContainer<?> container) throws SQLException {
        try (
                Connection connection = connection(container);
                Statement statement = connection.createStatement()
        ) {
            for (int tableIndex = 0; tableIndex < TABLE_COUNT_PER_DATABASE; tableIndex++) {
                statement.execute(messageTableDdl(tableIndex));
                statement.execute(sendRecordTableDdl(tableIndex));
            }

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS sys_tenant
                    (
                        id          BIGINT       NOT NULL,
                        tenant_code VARCHAR(64)  NOT NULL,
                        tenant_name VARCHAR(128) NOT NULL,
                        status      TINYINT      NOT NULL DEFAULT 1,
                        deleted     TINYINT      NOT NULL DEFAULT 0,
                        created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                        updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                                  ON UPDATE CURRENT_TIMESTAMP(3),
                        version     INT          NOT NULL DEFAULT 0,
                        PRIMARY KEY (id),
                        UNIQUE KEY uk_tenant_code (tenant_code)
                    )
                    """);
        }
    }

    static void clear(MySQLContainer<?> container) throws SQLException {
        try (
                Connection connection = connection(container);
                Statement statement = connection.createStatement()
        ) {
            // 先清从表，再清主表，方便未来补充真实外键实验
            for (int index = 0; index < TABLE_COUNT_PER_DATABASE; index++) {
                statement.execute("DELETE FROM notify_send_record_" + index);
                statement.execute("DELETE FROM notify_message_" + index);
            }
            statement.executeUpdate("DELETE FROM sys_tenant");
        }
    }

    static int count(
            MySQLContainer<?> container,
            String physicalTable,
            long tenantId
    ) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + physicalTable + " WHERE tenant_id = " + tenantId;

        try (
                Connection connection = connection(container);
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)
        ) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    static int countTenants(MySQLContainer<?> container) throws SQLException {
        try (
                Connection connection = connection(container);
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM sys_tenant")
        ) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    static Set<PhysicalNode> activeSendRecordNodes(
            MySQLContainer<?> first,
            MySQLContainer<?> second,
            long tenantId
    ) throws SQLException {
        MySQLContainer<?>[] containers = {first, second};
        Set<PhysicalNode> result = new LinkedHashSet<>();

        for (int dbIndex = 0; dbIndex < containers.length; dbIndex++) {
            for (int tableIndex = 0; tableIndex < TABLE_COUNT_PER_DATABASE; tableIndex++) {
                String table = "notify_send_record_" + tableIndex;
                if (count(containers[dbIndex], table, tenantId) > 0) {
                    result.add(new PhysicalNode(dbIndex, tableIndex));
                }
            }
        }
        return result;
    }

    static Connection connection(MySQLContainer<?> container) throws SQLException {

        return DriverManager.getConnection(
                container.getJdbcUrl(),
                container.getUsername(),
                container.getPassword()
        );
    }

    private static String messageTableDdl(int tableIndex) {
        return """
                CREATE TABLE IF NOT EXISTS notify_message_%d
                (
                    id             BIGINT      NOT NULL,
                    tenant_id      BIGINT      NOT NULL,
                    message_no     VARCHAR(64) NOT NULL,
                    message_status VARCHAR(32) NOT NULL,
                    created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_message_no (tenant_id, message_no)
                )
                """.formatted(tableIndex);
    }

    private static String sendRecordTableDdl(int tableIndex) {
        return """
                CREATE TABLE IF NOT EXISTS notify_send_record_%d
                (
                    id                  BIGINT       NOT NULL,
                    tenant_id           BIGINT       NOT NULL,
                    message_id          BIGINT       NOT NULL,
                    event_id            VARCHAR(64)  NOT NULL,
                    attempt_no          INT          NOT NULL,
                    channel_type        VARCHAR(32)  NOT NULL,
                    idempotency_key     VARCHAR(128) NOT NULL,
                    send_status         VARCHAR(32)  NOT NULL,
                    provider_message_id VARCHAR(128),
                    failure_code        VARCHAR(64),
                    failure_reason      VARCHAR(1000),
                    started_at          DATETIME(3)  NOT NULL,
                    finished_at         DATETIME(3),
                    created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                    updated_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                                        ON UPDATE CURRENT_TIMESTAMP(3),
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_send_attempt
                        (tenant_id, message_id, attempt_no),
                    UNIQUE KEY uk_send_idempotency
                        (tenant_id, idempotency_key),
                    KEY idx_send_event (tenant_id, event_id),
                    KEY idx_send_status (tenant_id, send_status)
                )
                """.formatted(tableIndex);
    }

    record PhysicalNode(int databaseIndex, int tableIndex) {
    }
}
