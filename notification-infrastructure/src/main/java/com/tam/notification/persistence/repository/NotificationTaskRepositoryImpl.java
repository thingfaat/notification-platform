package com.tam.notification.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tam.notification.domain.enums.ChannelType;
import com.tam.notification.domain.enums.TaskStatus;
import com.tam.notification.domain.task.NotificationTask;
import com.tam.notification.domain.task.NotificationTaskRepository;
import com.tam.notification.persistence.entity.NotificationTaskDO;
import com.tam.notification.persistence.mapper.NotificationTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class NotificationTaskRepositoryImpl implements NotificationTaskRepository {
    private final NotificationTaskMapper taskMapper;


    @Override
    public NotificationTask save(final NotificationTask task) {
        NotificationTaskDO data = toDO(task);
        taskMapper.insert(data);
        task.setId(data.getId());
        task.setTenantId(data.getTenantId());
        return task;
    }

    @Override
    public Optional<NotificationTask> findById(final Long id) {
        NotificationTaskDO data = taskMapper.selectById(id);
        return Optional.ofNullable(data).map(this::toDomain);
    }

    @Override
    public void update(final NotificationTask task) {
        taskMapper.updateById(toDO(task));
    }

    @Override
    public Optional<NotificationTask> findByRequestId(final Long applicationId, final String requestId) {
        NotificationTaskDO data = taskMapper.selectOne(Wrappers.<NotificationTaskDO>lambdaQuery().eq(NotificationTaskDO::getApplicationId, applicationId).eq(NotificationTaskDO::getRequestId, requestId));
        return Optional.ofNullable(data).map(this::toDomain);
    }

    private NotificationTaskDO toDO(NotificationTask task) {
        NotificationTaskDO data = new NotificationTaskDO();
        data.setId(task.getId());
        data.setTenantId(task.getTenantId());
        data.setApplicationId(task.getApplicationId());
        data.setRequestId(task.getRequestId());
        data.setTemplateId(task.getTemplateId());
        data.setChannelType(task.getChannelType().name());

        if (task.getTaskStatus() != null) {
            data.setTaskStatus(
                    task.getTaskStatus().name()
            );
        }

        data.setScheduleTime(task.getScheduleTime());
        data.setTotalCount(task.getTotalCount());
        data.setSuccessCount(task.getSuccessCount());
        data.setFailedCount(task.getFailedCount());
        data.setVersion(task.getVersion());
        return data;
    }

    private NotificationTask toDomain(NotificationTaskDO data) {
        NotificationTask task = new NotificationTask();
        task.setId(data.getId());
        task.setTenantId(data.getTenantId());
        task.setApplicationId(data.getApplicationId());
        task.setRequestId(data.getRequestId());
        task.setTemplateId(data.getTemplateId());
        task.setChannelType(ChannelType.valueOf(data.getChannelType()));
        task.setTaskStatus(TaskStatus.valueOf(data.getTaskStatus()));
        task.setScheduleTime(data.getScheduleTime());
        task.setTotalCount(data.getTotalCount());
        task.setSuccessCount(data.getSuccessCount());
        task.setFailedCount(data.getFailedCount());
        task.setVersion(data.getVersion());
        return task;
    }
}
