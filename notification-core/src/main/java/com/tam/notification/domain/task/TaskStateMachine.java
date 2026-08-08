package com.tam.notification.domain.task;

import com.tam.notification.domain.enums.TaskStatus;

import java.util.Map;
import java.util.Set;

public final class TaskStateMachine {
    private static final Map<TaskStatus, Set<TaskStatus>> TRANSITIONS = Map.of(
            TaskStatus.CREATED, Set.of(TaskStatus.PROCESSING, TaskStatus.CANCELLED),

            TaskStatus.PROCESSING, Set.of(TaskStatus.SUCCESS, TaskStatus.FAILED, TaskStatus.PARTIAL_SUCCESS, TaskStatus.CANCELLED),
            TaskStatus.PARTIAL_SUCCESS, Set.of(TaskStatus.PROCESSING, TaskStatus.SUCCESS, TaskStatus.FAILED, TaskStatus.CANCELLED),

            TaskStatus.FAILED, Set.of(TaskStatus.PROCESSING, TaskStatus.CANCELLED)
    );

    private TaskStateMachine() {
    }

    public static void checkTransition(TaskStatus current, TaskStatus target) {
        Set<TaskStatus> targets = TRANSITIONS.getOrDefault(current, Set.of());
        if (!targets.contains(target)) {
            throw new IllegalStateException(String.format("非法消息状态转换：%s, %s", current, target));
        }
    }
}
