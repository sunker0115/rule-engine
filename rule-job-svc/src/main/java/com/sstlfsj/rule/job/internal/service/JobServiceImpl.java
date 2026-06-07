package com.sstlfsj.rule.job.internal.service;

import com.sstlfsj.rule.config.api.dto.SceneDetailDto;
import com.sstlfsj.rule.config.api.service.SceneService;
import com.sstlfsj.rule.job.api.dto.CreateJobCommand;
import com.sstlfsj.rule.job.api.dto.JobDefinitionDto;
import com.sstlfsj.rule.job.api.dto.JobExecutionVO;
import com.sstlfsj.rule.job.api.service.JobService;
import com.sstlfsj.rule.job.internal.domain.JobDefinition;
import com.sstlfsj.rule.job.internal.domain.JobExecution;
import com.sstlfsj.rule.job.internal.repository.JobDefinitionMapper;
import com.sstlfsj.rule.job.internal.repository.JobExecutionMapper;
import com.sstlfsj.rule.job.internal.runner.JobRunner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** JobService 实现：Job CRUD + PULL Scene 拒绝校验 + 手动触发 + 执行记录查询。 */
@Service
@RequiredArgsConstructor
class JobServiceImpl implements JobService {

    private final JobDefinitionMapper jobMapper;
    private final JobExecutionMapper executionMapper;
    private final SceneService sceneService;
    private final JobScheduleManager scheduleManager;
    private final JobRunner jobRunner;

    @Override
    @Transactional
    public Long createJob(CreateJobCommand cmd) {
        rejectIfPullScene(cmd.tenantId(), cmd.sceneCode());
        JobDefinition def = new JobDefinition();
        def.setTenantId(Long.valueOf(cmd.tenantId()));
        def.setSceneCode(cmd.sceneCode());
        def.setCode(cmd.code());
        def.setName(cmd.name());
        def.setCronExpression(cmd.cronExpression());
        def.setSubjectQuery(cmd.subjectQuery());
        def.setEventType(cmd.eventType());
        def.setPayloadTemplate(cmd.payloadTemplate());
        def.setStatus("ACTIVE");
        def.setCreatedBy(cmd.actorId());
        jobMapper.insert(def);
        scheduleManager.register(def);
        return def.getId();
    }

    @Override
    @Transactional
    public void enableJob(String tenantId, Long jobId) {
        JobDefinition def = findJob(tenantId, jobId);
        rejectIfPullScene(tenantId, def.getSceneCode());
        def.setStatus("ACTIVE");
        def.setUpdatedAt(LocalDateTime.now());
        jobMapper.updateById(def);
        scheduleManager.register(def);
    }

    @Override
    @Transactional
    public void disableJob(String tenantId, Long jobId) {
        JobDefinition def = findJob(tenantId, jobId);
        def.setStatus("DISABLED");
        def.setUpdatedAt(LocalDateTime.now());
        jobMapper.updateById(def);
        scheduleManager.unregister(jobId);
    }

    @Override
    public List<JobDefinitionDto> listJobs(String tenantId) {
        return jobMapper.findByTenantId(Long.valueOf(tenantId)).stream()
                .map(JobServiceImpl::toDto)
                .toList();
    }

    @Override
    public JobDefinitionDto getJob(String tenantId, Long jobId) {
        return toDto(findJob(tenantId, jobId));
    }

    @Override
    public JobExecutionVO triggerOnce(String tenantId, Long jobId) {
        JobDefinition def = findJob(tenantId, jobId);
        return toVO(jobRunner.run(def));
    }

    @Override
    public List<JobExecutionVO> recentExecutions(String tenantId, Long jobId, int limit) {
        findJob(tenantId, jobId);
        return executionMapper.findRecent(jobId, limit).stream()
                .map(JobServiceImpl::toVO)
                .toList();
    }

    /** 绑定 Scene 为 PULL 时拒绝——PULL 是同步业务调用语义，定时触发无意义（§3.10）。 */
    private void rejectIfPullScene(String tenantId, String sceneCode) {
        SceneDetailDto scene = sceneService.getScene(tenantId, sceneCode);
        if ("PULL".equals(scene.dominantMode())) {
            throw new IllegalArgumentException("PULL Scene 不允许绑定 Job: " + sceneCode);
        }
    }

    private JobDefinition findJob(String tenantId, Long jobId) {
        JobDefinition def = jobMapper.selectById(jobId);
        if (def == null || !def.getTenantId().equals(Long.valueOf(tenantId))) {
            throw new IllegalArgumentException("Job 不存在: " + jobId);
        }
        return def;
    }

    private static JobDefinitionDto toDto(JobDefinition def) {
        return new JobDefinitionDto(
                def.getId(),
                String.valueOf(def.getTenantId()),
                def.getSceneCode(),
                def.getCode(),
                def.getName(),
                def.getCronExpression(),
                def.getSubjectQuery(),
                def.getEventType(),
                def.getPayloadTemplate(),
                def.getStatus());
    }

    private static JobExecutionVO toVO(JobExecution exec) {
        return new JobExecutionVO(
                exec.getId(),
                exec.getJobDefinitionId(),
                String.valueOf(exec.getTenantId()),
                exec.getTriggerAt(),
                exec.getStatus(),
                exec.getSubjectCount() != null ? exec.getSubjectCount() : 0,
                exec.getSuccessCount() != null ? exec.getSuccessCount() : 0,
                exec.getErrorCount() != null ? exec.getErrorCount() : 0,
                exec.getErrorSummary(),
                exec.getFinishedAt());
    }
}
