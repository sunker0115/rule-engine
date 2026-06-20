package com.sstlfsj.rule.job.internal.runner;

import com.sstlfsj.rule.job.internal.domain.ScheduledTask;
import com.sstlfsj.rule.kernel.api.spi.task.TaskExecutor;
import com.sstlfsj.rule.kernel.api.spi.task.TaskRunContext;
import com.sstlfsj.rule.kernel.api.spi.task.TaskRunResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 按开放类型名(String)收集 {@link TaskExecutor} 并路由。Spring 跨模块注入 {@code List<TaskExecutor>} 自动收集
 * (XXL {@code @XxlJob} bean 扫描式解耦,job-svc 不依赖各 handler 模块)。
 *
 * <p>派发时按 task_type 取 executor,用 executor.configType() 把 config 原始 JSON 反序列化成该 handler 的 typed config。
 */
@Slf4j
@Component
public class TaskExecutorRegistry {

    private final Map<String, TaskExecutor<?>> byType = new HashMap<>();
    private final ObjectMapper objectMapper;

    /**
     * @param executors    Spring 收集到的全部 executor(各模块自声明 type)
     * @param objectMapper 全局 ObjectMapper,用于 config JSON↔typed 反序列化
     */
    public TaskExecutorRegistry(List<TaskExecutor<?>> executors, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        for (TaskExecutor<?> e : executors) {
            if (byType.putIfAbsent(e.type(), e) != null) {
                throw new IllegalStateException("多个 TaskExecutor 声明同一 type=" + e.type());
            }
        }
    }

    /**
     * 路由执行:按 task_type 取 executor → config JSON 反序列化成 executor.configType() → 执行。
     *
     * @param task 触发的任务
     * @param ctx  运行上下文(taskRunId/taskId/tenantId/cursor)
     * @return 执行结果
     */
    public TaskRunResult dispatch(ScheduledTask task, TaskRunContext ctx) {
        TaskExecutor<?> ex = byType.get(task.getTaskType());
        if (ex == null) {
            throw new IllegalStateException("无 TaskExecutor 处理 type=" + task.getTaskType());
        }
        Object cfg = task.getConfig() == null ? null
                : objectMapper.readValue(task.getConfig(), ex.configType());
        return invoke(ex, ctx, cfg);
    }

    @SuppressWarnings("unchecked")
    private <C> TaskRunResult invoke(TaskExecutor<C> ex, TaskRunContext ctx, Object cfg) {
        return ex.execute(ctx, (C) cfg);
    }
}
