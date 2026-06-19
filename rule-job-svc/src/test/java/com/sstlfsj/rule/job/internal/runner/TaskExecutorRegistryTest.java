package com.sstlfsj.rule.job.internal.runner;

import com.sstlfsj.rule.job.api.*;
import com.sstlfsj.rule.job.internal.domain.ScheduledTask;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskExecutorRegistryTest {

    private final TaskExecutor<TriggerConfig> triggerExec = new TaskExecutor<>() {
        public TaskType type() { return TaskType.TRIGGER; }
        public Class<TriggerConfig> configType() { return TriggerConfig.class; }
        public TaskRunResult execute(TaskRunContext ctx, TriggerConfig config) {
            return new TaskRunResult(TaskExecutionStatus.SUCCESS, 1, 1, 0, null);
        }
    };

    @Test
    void dispatch_routesByType() {
        TaskExecutorRegistry reg = new TaskExecutorRegistry(List.of(triggerExec));
        ScheduledTask task = new ScheduledTask();
        task.setId(1L); task.setTenantId(7L);
        task.setTaskType(TaskType.TRIGGER);
        task.setConfig(new TriggerConfig("s", "e", null));
        assertThat(reg.dispatch(task, 99L).status()).isEqualTo(TaskExecutionStatus.SUCCESS);
    }

    @Test
    void duplicateType_throwsAtConstruction() {
        assertThatThrownBy(() -> new TaskExecutorRegistry(List.of(triggerExec, triggerExec)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void noExecutorForType_throws() {
        TaskExecutorRegistry reg = new TaskExecutorRegistry(List.of());
        ScheduledTask task = new ScheduledTask();
        task.setTaskType(TaskType.TRIGGER);
        assertThatThrownBy(() -> reg.dispatch(task, 99L)).isInstanceOf(IllegalStateException.class);
    }
}
