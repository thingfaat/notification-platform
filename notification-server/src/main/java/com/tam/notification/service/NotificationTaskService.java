package com.tam.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tam.notification.common.exception.BusinessException;
import com.tam.notification.common.exception.CommonErrorCode;
import com.tam.notification.domain.enums.ChannelType;
import com.tam.notification.domain.enums.MessageStatus;
import com.tam.notification.domain.enums.TaskStatus;
import com.tam.notification.domain.message.NotificationMessage;
import com.tam.notification.domain.message.NotificationMessageRepository;
import com.tam.notification.domain.task.NotificationTask;
import com.tam.notification.domain.task.NotificationTaskRepository;
import com.tam.notification.domain.template.MessageTemplate;
import com.tam.notification.domain.template.MessageTemplateRepository;
import com.tam.notification.domain.template.TemplateRenderer;
import com.tam.notification.dto.CreateNotificationTaskRequest;
import com.tam.notification.dto.RecipientRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationTaskService {
    private final MessageTemplateRepository templateRepository;
    private final NotificationTaskRepository taskRepository;
    private final NotificationMessageRepository messageRepository;
    private final TemplateRenderer templateRenderer;

    @Transactional
    public NotificationTask create(CreateNotificationTaskRequest request) {
        // 检查同一个application下面requestId是否已经存在
        taskRepository.findByRequestId(request.applicationId(), request.requestId()).ifPresent(existing -> {
            throw new BusinessException(CommonErrorCode.BUSINESS_ERROR, "requestId已经存在");
        });
        // 查询模板
        MessageTemplate template = templateRepository.findById(request.templateId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.BUSINESS_ERROR, "消息模板不存在"));

        // 校验模板是否属于当前application
        if (!Objects.equals(template.getApplicationId(), request.applicationId())) {
            throw new BusinessException(CommonErrorCode.BUSINESS_ERROR, "消息模板不属于当前应用");
        }

        // 模版必须处于启用状态
        if (!Objects.equals(template.getStatus(), 1)) {
            throw new BusinessException(CommonErrorCode.BUSINESS_ERROR, "消息模板未启用");
        }

        // 创建task
        NotificationTask task = new NotificationTask();
        task.setApplicationId(request.applicationId());
        task.setRequestId(request.requestId());
        task.setTemplateId(template.getId());
        task.setChannelType(ChannelType.valueOf(template.getChannelType()));
        task.setTaskStatus(TaskStatus.CREATED);
        task.setScheduleTime(request.scheduleTime());
        task.setTotalCount(request.recipients().size());
        task.setSuccessCount(0);
        task.setFailedCount(0);

        // 保存task
        taskRepository.save(task);
        // 为每个接收人创建一条message
        for (final var recipient : request.recipients()) {
            createMessage(task, template, recipient);
        }

        return task;
    }

    private void createMessage(NotificationTask task, MessageTemplate template, RecipientRequest recipient) {
        // 渲染模板
        String renderedContent = templateRenderer.render(template.getTemplateContent(), recipient.params());
        // 创建message
        NotificationMessage message = new NotificationMessage();
        message.setTaskId(task.getId());
        message.setMessageNo(generateMessageNo());
        message.setReceiver(recipient.receiver());
        message.setTemplateParams(recipient.params());
        message.setRenderedContent(renderedContent);
        message.setMessageStatus(MessageStatus.CREATED);
        message.setRetryCount(0);
        // 3. 保存Message
        messageRepository.save(message);
    }

    public NotificationTask get(Long id) {
        return taskRepository.findById(id).orElseThrow(() -> new BusinessException(CommonErrorCode.BUSINESS_ERROR, "任务不存在"));
    }

    private String generateMessageNo() {
        return "MSG_" + UUID.randomUUID().toString().replace("-", "");
    }
}
