package com.sstlfsj.rule.job.internal.runner;

import com.sstlfsj.rule.eval.api.service.EvalService;
import com.sstlfsj.rule.job.internal.domain.JobDefinition;
import com.sstlfsj.rule.job.internal.domain.JobExecution;
import com.sstlfsj.rule.job.internal.repository.JobExecutionMapper;
import com.sstlfsj.rule.job.internal.subject.SubjectQueryRunner;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Job 单次运行：查主体 → 合成 RuleEvent → {@link EvalService#acceptEvent} 注入 → 记 JobExecution。
 *
 * <p>下游 Matcher / AST / Action 对 Job 完全无感（D11）。{@code acceptEvent} 为异步 PUSH，
 * 成功入队即计入 successCount（= 成功注入评估链路的主体数），不等待评估结果。
 */
@Component
@RequiredArgsConstructor
public class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);
    private static final int ERROR_SUMMARY_MAX = 2000;

    private final SubjectQueryRunner subjectQueryRunner;
    private final PayloadTemplateRenderer payloadRenderer;
    private final EvalService evalService;
    private final JobExecutionMapper executionMapper;

    /**
     * 执行一次 Job。先落 RUNNING 记录拿到 jobRunId，再逐主体注入，最后落终态。
     *
     * @param def Job 定义
     * @return 本次执行记录（终态）
     */
    public JobExecution run(JobDefinition def) {
        JobExecution exec = new JobExecution();
        exec.setJobDefinitionId(def.getId());
        exec.setTenantId(def.getTenantId());
        exec.setTriggerAt(LocalDateTime.now());
        exec.setStatus("RUNNING");
        exec.setSubjectCount(0);
        exec.setSuccessCount(0);
        exec.setErrorCount(0);
        executionMapper.insert(exec);
        long jobRunId = exec.getId();

        int success = 0;
        int error = 0;
        List<String> errors = new ArrayList<>();
        try {
            List<Map<String, Object>> subjects = subjectQueryRunner.query(def.getSubjectQuery());
            exec.setSubjectCount(subjects.size());
            String tenantId = String.valueOf(def.getTenantId());
            Instant now = Instant.now();
            for (Map<String, Object> row : subjects) {
                String subjectId = String.valueOf(row.get("subjectId"));
                try {
                    String eventId = EventIdHasher.hash(jobRunId, subjectId);
                    Map<String, Object> payload = payloadRenderer.render(def.getPayloadTemplate(), row);
                    RuleEvent event = new RuleEvent(tenantId, def.getSceneCode(), def.getEventType(),
                            subjectId, eventId, now, payload, null);
                    if (evalService.acceptEvent(event)) {
                        success++;
                    } else {
                        error++;
                        errors.add("subjectId=" + subjectId + " 被拒绝（队列满）");
                    }
                } catch (RuntimeException e) {
                    error++;
                    errors.add("subjectId=" + subjectId + " 异常: " + e.getMessage());
                    log.warn("Job 主体注入失败 jobRunId={} subjectId={}", jobRunId, subjectId, e);
                }
            }
            exec.setStatus(error == 0 ? "SUCCESS" : (success > 0 ? "PARTIAL_FAIL" : "FAILED"));
        } catch (RuntimeException e) {
            // 主体查询阶段失败 → 整体 FAILED
            exec.setStatus("FAILED");
            errors.add("主体查询失败: " + e.getMessage());
            log.error("Job 主体查询失败 jobRunId={} jobCode={}", jobRunId, def.getCode(), e);
        }
        exec.setSuccessCount(success);
        exec.setErrorCount(error);
        exec.setFinishedAt(LocalDateTime.now());
        if (!errors.isEmpty()) {
            exec.setErrorSummary(truncate(String.join("; ", errors)));
        }
        executionMapper.updateById(exec);
        return exec;
    }

    private static String truncate(String s) {
        return s.length() <= ERROR_SUMMARY_MAX ? s : s.substring(0, ERROR_SUMMARY_MAX);
    }
}
