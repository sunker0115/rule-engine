package com.sstlfsj.rule.job.internal.runner;

import com.sstlfsj.rule.job.api.TaskConfig;
import com.sstlfsj.rule.job.api.TaskExecutor;
import com.sstlfsj.rule.job.api.TaskRunResult;
import com.sstlfsj.rule.job.api.TaskType;
import com.sstlfsj.rule.job.internal.domain.ScheduledTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 按 TaskType 收集 TaskExecutor 并路由。Spring 注入 List<TaskExecutor> 自动收集。 */
@Slf4j
@Component
public class TaskExecutorRegistry {

    private final Map<TaskType, TaskExecutor<?>> byType = new EnumMap<>(TaskType.class);

    public TaskExecutorRegistry(List<TaskExecutor<?>> executors) {
        for (TaskExecutor<?> e : executors) {
            if (byType.putIfAbsent(e.type(), e) != null) {
                throw new IllegalStateException("多个 TaskExecutor 声明同一 type=" + e.type());
            }
        }
    }

    /**
     * 路由执行:校验 config 子类型与 executor.configType() 一致,转型后执行。
     *
     * @param task 触发的任务
     * @return 执行结果
     */
    @SuppressWarnings("unchecked")
    public <C extends TaskConfig> TaskRunResult dispatch(ScheduledTask task) {
        TaskExecutor<C> executor = (TaskExecutor<C>) byType.get(task.getTaskType());
        if (executor == null) {
            throw new IllegalStateException("无 TaskExecutor 处理 type=" + task.getTaskType());
        }
        TaskConfig config = task.getConfig();
        if (!executor.configType().isInstance(config)) {
            throw new IllegalStateException("task " + task.getId() + " config 类型 "
                    + (config == null ? "null" : config.getClass().getSimpleName())
                    + " 与 executor.configType=" + executor.configType().getSimpleName() + " 不符");
        }
        return executor.execute(task.getId(), task.getTenantId(), (C) executor.configType().cast(config));
    }
}
