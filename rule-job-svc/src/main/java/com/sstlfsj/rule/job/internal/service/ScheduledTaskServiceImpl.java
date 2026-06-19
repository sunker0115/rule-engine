package com.sstlfsj.rule.job.internal.service;

import com.sstlfsj.rule.config.api.dto.SceneDetailDto;
import com.sstlfsj.rule.config.api.service.SceneService;
import com.sstlfsj.rule.job.api.TaskStatus;
import com.sstlfsj.rule.job.api.TriggerConfig;
import com.sstlfsj.rule.job.api.dto.ScheduledTaskExecutionVO;
import com.sstlfsj.rule.job.api.dto.ScheduledTaskVO;
import com.sstlfsj.rule.job.api.service.ScheduledTaskService;
import com.sstlfsj.rule.job.internal.domain.ScheduledTask;
import com.sstlfsj.rule.job.internal.domain.ScheduledTaskExecution;
import com.sstlfsj.rule.job.internal.repository.ScheduledTaskExecutionMapper;
import com.sstlfsj.rule.job.internal.repository.ScheduledTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** ScheduledTaskService 实现：任务启用 / 禁用 / 查询 / 手动触发 / 执行记录查询（定义走 {@code @TriggerTask} 注解）。 */
@Service
@RequiredArgsConstructor
class ScheduledTaskServiceImpl implements ScheduledTaskService {

    private final ScheduledTaskMapper taskMapper;
    private final ScheduledTaskExecutionMapper executionMapper;
    private final SceneService sceneService;
    private final ScheduledTaskScheduleManager scheduleManager;

    @Override
    @Transactional
    public void enable(Long tenantId, Long taskId) {
        ScheduledTask task = findTask(tenantId, taskId);
        rejectIfPullScene(tenantId, task);
        task.setStatus(TaskStatus.ACTIVE);
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        scheduleManager.register(task);
    }

    @Override
    @Transactional
    public void disable(Long tenantId, Long taskId) {
        ScheduledTask task = findTask(tenantId, taskId);
        task.setStatus(TaskStatus.DISABLED);
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        scheduleManager.unregister(taskId);
    }

    @Override
    public List<ScheduledTaskVO> list(Long tenantId) {
        return taskMapper.findByTenant(tenantId).stream()
                .map(ScheduledTaskServiceImpl::toVO)
                .toList();
    }

    @Override
    public ScheduledTaskVO get(Long tenantId, Long taskId) {
        return toVO(findTask(tenantId, taskId));
    }

    @Override
    public ScheduledTaskExecutionVO triggerOnce(Long tenantId, Long taskId) {
        findTask(tenantId, taskId);
        return toVO(scheduleManager.runOnce(taskId));
    }

    @Override
    public List<ScheduledTaskExecutionVO> recentExecutions(Long tenantId, Long taskId, int limit) {
        findTask(tenantId, taskId);
        return executionMapper.recentByTask(taskId, limit).stream()
                .map(ScheduledTaskServiceImpl::toVO)
                .toList();
    }

    /** TRIGGER 任务绑定 Scene 为 PULL 时拒绝启用——PULL 是同步业务调用语义，定时触发无意义（§3.10）。 */
    private void rejectIfPullScene(Long tenantId, ScheduledTask task) {
        if (task.getConfig() instanceof TriggerConfig trigger) {
            SceneDetailDto scene = sceneService.getScene(tenantId, trigger.sceneCode());
            if ("PULL".equals(scene.dominantMode())) {
                throw new IllegalArgumentException("PULL Scene 不允许绑定 TRIGGER 任务: " + trigger.sceneCode());
            }
        }
    }

    private ScheduledTask findTask(Long tenantId, Long taskId) {
        ScheduledTask task = taskMapper.selectById(taskId);
        if (task == null || !task.getTenantId().equals(tenantId)) {
            throw new IllegalArgumentException("调度任务不存在: " + taskId);
        }
        return task;
    }

    private static ScheduledTaskVO toVO(ScheduledTask task) {
        return new ScheduledTaskVO(
                task.getId(),
                task.getTenantId(),
                task.getCode(),
                task.getName(),
                task.getTaskType(),
                task.getCron(),
                task.getConfig(),
                task.getStatus().name(),
                task.getCreatedAt() != null ? task.getCreatedAt().toInstant(ZoneOffset.UTC) : null,
                task.getUpdatedAt() != null ? task.getUpdatedAt().toInstant(ZoneOffset.UTC) : null);
    }

    private static ScheduledTaskExecutionVO toVO(ScheduledTaskExecution exec) {
        return new ScheduledTaskExecutionVO(
                exec.getId(),
                exec.getScheduledTaskId(),
                exec.getStatus().name(),
                exec.getProcessedCount(),
                exec.getSuccessCount(),
                exec.getErrorCount(),
                exec.getErrorSummary(),
                exec.getTriggerAt() != null ? exec.getTriggerAt().toInstant(ZoneOffset.UTC) : null,
                exec.getFinishedAt() != null ? exec.getFinishedAt().toInstant(ZoneOffset.UTC) : null);
    }
}
