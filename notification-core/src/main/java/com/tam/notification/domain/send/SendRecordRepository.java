package com.tam.notification.domain.send;

import java.time.LocalDateTime;
import java.util.Optional;

public interface SendRecordRepository {

    SendRecord save(SendRecord sendRecord);

    Optional<SendRecord> findByMessageIdAndAttemptNo(Long messageId, Integer attemptNo);


    /**
     * 完成成功发送
     *
     * @param id                发送记录全局id，用于精确定位业务行
     * @param messageId         day23分片键，保证未来接入shardingsphere后单点路由
     * @param providerMessageId 渠道侧消息id
     * @param finishedAt        完成时间
     * @return
     */
    boolean markSuccess(
            Long id,
            Long messageId,
            String providerMessageId,
            LocalDateTime finishedAt
    );

    /**
     * 完成失败发送
     * messageId不是冗余参数：没有它时，按messageId分片的update
     * 无法精确路由，只能广播到全部物理节点
     *
     * @param id
     * @param messageId
     * @param failureCode
     * @param failureReason
     * @param finishedAt
     * @return
     */
    boolean markFailed(
            Long id,
            Long messageId,
            String failureCode,
            String failureReason,
            LocalDateTime finishedAt
    );
}
