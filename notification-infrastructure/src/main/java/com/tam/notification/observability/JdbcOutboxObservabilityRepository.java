package com.tam.notification.observability;

import com.tam.notification.domain.observability.OutboxBacklogSnapshot;
import com.tam.notification.domain.observability.OutboxObservabilityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 使用一条聚合 SQL 读取平台级 Outbox 状态。
 *
 * <p>这里故意不走 TenantLineInterceptor，因为监控需要看到全平台积压；
 * SQL 只返回数量和年龄，不返回跨租户业务数据。</p>
 */
@Repository
@RequiredArgsConstructor
public class JdbcOutboxObservabilityRepository
        implements OutboxObservabilityRepository {
    private final JdbcTemplate jdbcTemplate;

    @Override
    public OutboxBacklogSnapshot loadSnapshot() {
        /**
         * COALESCE：避免 SQL 返回 NULL，如果SUM聚合为NULL，则返回0
         */
        OutboxBacklogSnapshot snapshot = jdbcTemplate.queryForObject(
                """
                        SELECT
                            COALESCE(SUM(
                                CASE
                                    WHEN publish_status IN ('NEW', 'FAILED', 'PROCESSING')
                                    THEN 1 ELSE 0
                                END
                            ), 0) AS pending_count,
                            COALESCE(SUM(
                                CASE WHEN publish_status = 'DEAD' THEN 1 ELSE 0 END
                            ), 0) AS dead_count,
                            COALESCE(
                                TIMESTAMPDIFF(
                                    SECOND,
                                    MIN(
                                        CASE
                                            WHEN publish_status IN ('NEW', 'FAILED', 'PROCESSING')
                                            THEN created_at
                                            ELSE NULL
                                        END
                                    ),
                                    NOW(3)
                                ),
                                0
                            ) AS oldest_pending_age_seconds
                        FROM notify_outbox
                        """,
                (resultSet, rowNum) -> new OutboxBacklogSnapshot(
                        resultSet.getLong("pending_count"),
                        resultSet.getLong("dead_count"),
                        resultSet.getLong("oldest_pending_age_seconds")
                )
        );

        if (snapshot == null) {
            // 聚合 SQL 正常情况下始终返回一行；防御性兜底避免 Gauge 线程 NPE。
            return new OutboxBacklogSnapshot(0, 0, 0);
        }
        return snapshot;
    }
}
