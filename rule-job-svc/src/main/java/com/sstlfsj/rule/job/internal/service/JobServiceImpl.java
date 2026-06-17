package com.sstlfsj.rule.job.internal.service;

import com.sstlfsj.rule.config.api.dto.SceneDetailDto;
import com.sstlfsj.rule.config.api.service.SceneService;
import com.sstlfsj.rule.job.api.dto.JobDefinitionDto;
import com.sstlfsj.rule.job.api.dto.JobExecutionVO;
import com.sstlfsj.rule.job.api.service.JobService;
import com.sstlfsj.rule.job.internal.domain.JobDefinition;
import com.sstlfsj.rule.job.internal.domain.JobExecution;
import com.sstlfsj.rule.job.internal.domain.JobStatus;
import com.sstlfsj.rule.job.internal.repository.JobDefinitionMapper;
import com.sstlfsj.rule.job.internal.repository.JobExecutionMapper;
import com.sstlfsj.rule.job.internal.runner.JobRunner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

/** JobService 实现：Job 启用 / 禁用 / 查询 / 手动触发 / 执行记录查询（D48：定义走 @RuleJob 注解）。 */
@Service
@RequiredArgsConstructor
class JobServiceImpl implements JobService {

    private final JobDefinitionMapper jobMapper;
    private final JobExecutionMapper executionMapper;
    private final SceneService sceneService;
    private final JobScheduleManager scheduleManager;
    private final JobRunner jobRunner;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public void enableJob(Long tenantId, Long jobId) {
        JobDefinition def = findJob(tenantId, jobId);
        rejectIfPullScene(tenantId, def.getSceneCode());
        def.setStatus(JobStatus.ACTIVE);
        def.setUpdatedAt(LocalDateTime.now());
        jobMapper.updateById(def);
        scheduleManager.register(def);
    }

    @Override
    @Transactional
    public void disableJob(Long tenantId, Long jobId) {
        JobDefinition def = findJob(tenantId, jobId);
        def.setStatus(JobStatus.DISABLED);
        def.setUpdatedAt(LocalDateTime.now());
        jobMapper.updateById(def);
        scheduleManager.unregister(jobId);
    }

    @Override
    public List<JobDefinitionDto> listJobs(Long tenantId) {
        return jobMapper.findByTenantId(tenantId).stream()
                .map(this::toDto)
                .toList();
    }

    @Override
    public JobDefinitionDto getJob(Long tenantId, Long jobId) {
        return toDto(findJob(tenantId, jobId));
    }

    @Override
    public JobExecutionVO triggerOnce(Long tenantId, Long jobId) {
        JobDefinition def = findJob(tenantId, jobId);
        return toVO(jobRunner.run(def));
    }

    @Override
    public List<JobExecutionVO> recentExecutions(Long tenantId, Long jobId, int limit) {
        findJob(tenantId, jobId);
        return executionMapper.findRecent(jobId, limit).stream()
                .map(JobServiceImpl::toVO)
                .toList();
    }

    /** 绑定 Scene 为 PULL 时拒绝——PULL 是同步业务调用语义，定时触发无意义（§3.10）。 */
    private void rejectIfPullScene(Long tenantId, String sceneCode) {
        SceneDetailDto scene = sceneService.getScene(tenantId, sceneCode);
        if ("PULL".equals(scene.dominantMode())) {
            throw new IllegalArgumentException("PULL Scene 不允许绑定 Job: " + sceneCode);
        }
    }

    private JobDefinition findJob(Long tenantId, Long jobId) {
        JobDefinition def = jobMapper.selectById(jobId);
        if (def == null || !def.getTenantId().equals(tenantId)) {
            throw new IllegalArgumentException("Job 不存在: " + jobId);
        }
        return def;
    }

    private JobDefinitionDto toDto(JobDefinition def) {
        return new JobDefinitionDto(
                def.getId(),
                def.getTenantId(),
                def.getSceneCode(),
                def.getCode(),
                def.getName(),
                def.getCronExpression(),
                objectMapper.readValue(def.getSubjectQuery(), com.sstlfsj.rule.job.api.SubjectQuery.class),
                def.getEventType(),
                def.getStatus().name());
    }

    private static JobExecutionVO toVO(JobExecution exec) {
        return new JobExecutionVO(
                exec.getId(),
                exec.getJobDefinitionId(),
                exec.getTenantId(),
                exec.getTriggerAt(),
                exec.getStatus().name(),
                exec.getSubjectCount() != null ? exec.getSubjectCount() : 0,
                exec.getSuccessCount() != null ? exec.getSuccessCount() : 0,
                exec.getErrorCount() != null ? exec.getErrorCount() : 0,
                exec.getErrorSummary(),
                exec.getFinishedAt());
    }
}
