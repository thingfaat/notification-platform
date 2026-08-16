package com.tam.notification.listener;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Worker实例身份
 * 一个workerJVM内有普通、DLQ、广播等多个Consumer，因此最终instanceName使用“实例ID+监听器角色”，避免同进程内重名
 */
@Component
public class WorkerIdentity {
    private final String instanceId;

    public WorkerIdentity(
            @Value("${notification.worker.instance-id}") String instanceId
    ) {
        if (!StringUtils.hasText(instanceId)) {
            // 身份为空时直接启动失败，比两个实例悄悄使用同名更容易排查。
            throw new IllegalArgumentException("notification.worker.instance-id 不能为空");
        }
        this.instanceId = instanceId;
    }

    public String instanceId() {
        return instanceId;
    }

    /**
     * Rocket mq spring 2.3.3不会解析注释instanceName中的占位符，所以必须在Consumer启动前通过生命周期回调设置
     *
     * @param consumer
     * @param listenerRole
     */
    public void configure(
            DefaultMQPushConsumer consumer,
            String listenerRole
    ) {
        consumer.setInstanceName(instanceId + "-" + listenerRole);
    }
}
