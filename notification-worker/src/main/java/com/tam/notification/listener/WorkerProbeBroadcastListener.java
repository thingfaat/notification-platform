package com.tam.notification.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQPushConsumerLifecycleListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "notification.mq.broadcast-probe",
        name = "enabled",
        havingValue = "true"
)
@RocketMQMessageListener(
        topic = "${notification.mq.broadcast-probe.topic}",
        consumerGroup = "${notification.mq.broadcast-probe.consumer-group}",
        messageModel = MessageModel.BROADCASTING,
        consumeMode = ConsumeMode.CONCURRENTLY
)
public class WorkerProbeBroadcastListener implements RocketMQListener<MessageExt>, RocketMQPushConsumerLifecycleListener {

    private final WorkerIdentity workerIdentity;

    @Override
    public void prepareStart(final DefaultMQPushConsumer consumer) {
        /// 广播实例必须显式区分，否则两个本地 JVM 可能使用相同实例名。
        workerIdentity.configure(consumer, "broadcast-probe");
    }

    @Override
    public void onMessage(final MessageExt message) {
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);

        log.info(
                "收到广播探针，worker={}, payload={}, mqMsgId={}, queueId={}, queueOffset={}",
                workerIdentity.instanceId(),
                payload,
                message.getMsgId(),
                message.getQueueId(),
                message.getQueueOffset()
        );
    }
}
