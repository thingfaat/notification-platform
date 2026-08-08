package com.tam.notification.domain.message;

import com.tam.notification.domain.enums.MessageStatus;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
public class NotificationMessage {
    private Long id;
    private Long tenantId;
    private Long applicationId;
    private Long taskId;
    private String messageNo;
    private String receiver;
    private Map<String, Object> templateParams;
    private String renderedContent;
    private MessageStatus messageStatus;
    private Integer retryCount;
    private LocalDateTime nextRetryTime;
    private String providerMessageId;
    private String failureCode;
    private String failureReason;

    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public void changeStatus(MessageStatus targetStatus) {
        MessageStateMachine.checkTransition(this.messageStatus, targetStatus);
        this.messageStatus = targetStatus;
    }
}
