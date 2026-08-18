package com.tam.notification.domain.observability;

/**
 * 平台级 Outbox 可观测性端口。
 *
 * <p>业务 Repository 继续负责保存、Claim 和状态流转；
 * 该端口只负责低频只读聚合，避免把监控 SQL 放进 Server。</p>
 */
public interface OutboxObservabilityRepository {

    OutboxBacklogSnapshot loadSnapshot();
}
