package com.sstlfsj.rule.job.internal.runner;

import com.sstlfsj.rule.job.api.TriggerConfig;
import com.sstlfsj.rule.job.internal.domain.ScheduledTask;
import com.sstlfsj.rule.kernel.api.spi.task.TaskExecutionStatus;
import com.sstlfsj.rule.kernel.api.spi.task.TaskExecutor;
import com.sstlfsj.rule.kernel.api.spi.task.TaskRunContext;
import com.sstlfsj.rule.kernel.api.spi.task.TaskRunResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskExecutorRegistryTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final AtomicReference<TriggerConfig> received = new AtomicReference<>();

    private final TaskExecutor<TriggerConfig> triggerExec = new TaskExecutor<>() {
        public String type() { return "TRIGGER"; }
        public Class<TriggerConfig> configType() { return TriggerConfig.class; }
        public TaskRunResult execute(TaskRunContext ctx, TriggerConfig config) {
            received.set(config);
            return new TaskRunResult(TaskExecutionStatus.SUCCESS, 1, 1, 0, null, null);
        }
    };

    private TaskRunContext ctx() {
        return new TaskRunContext(99L, 1L, 7L, null);
    }

    @Test
    void dispatch_routesByTypeAndDeserializesConfigJson() {
        TaskExecutorRegistry reg = new TaskExecutorRegistry(List.of(triggerExec), mapper);
        ScheduledTask task = new ScheduledTask();
        task.setId(1L);
        task.setTenantId(7L);
        task.setTaskType("TRIGGER");
        task.setConfig("{\"sceneCode\":\"s\",\"eventType\":\"e\",\"subjectQuery\":null}");

        TaskRunResult r = reg.dispatch(task, ctx());

        assertThat(r.status()).isEqualTo(TaskExecutionStatus.SUCCESS);
        // config JSON 被反序列化成 typed TriggerConfig 传给 executor
        assertThat(received.get()).isNotNull();
        assertThat(received.get().sceneCode()).isEqualTo("s");
        assertThat(received.get().eventType()).isEqualTo("e");
    }

    @Test
    void duplicateType_throwsAtConstruction() {
        assertThatThrownBy(() -> new TaskExecutorRegistry(List.of(triggerExec, triggerExec), mapper))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void noExecutorForType_throws() {
        TaskExecutorRegistry reg = new TaskExecutorRegistry(List.of(), mapper);
        ScheduledTask task = new ScheduledTask();
        task.setTaskType("TRIGGER");
        assertThatThrownBy(() -> reg.dispatch(task, ctx())).isInstanceOf(IllegalStateException.class);
    }
}
