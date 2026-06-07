package com.sstlfsj.rule.job.internal.runner;

import com.sstlfsj.rule.eval.api.service.EvalService;
import com.sstlfsj.rule.job.api.JobTarget;
import com.sstlfsj.rule.job.internal.domain.JobDefinition;
import com.sstlfsj.rule.job.internal.domain.JobExecution;
import com.sstlfsj.rule.job.internal.repository.JobExecutionMapper;
import com.sstlfsj.rule.job.internal.subject.SubjectQueryRunner;
import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Job 单次运行：查主体 → 合成 RuleEvent → {@link EvalService#acceptEvent} 注入 → 记 JobExecution。
 *
 * <p>下游 Matcher / AST / Action 对 Job 完全无感（D11）。{@code acceptEvent} 为异步 PUSH，
 * 成功入队即计入 successCount（= 成功注入评估链路的主体数），不等待评估结果。
 *
 * <p>主体经 {@link SubjectQueryRunner#forEachTarget} 逐个推入处理：小数据量无参返回 List，大数据量
 * 走 JobPage 分页拉取（仿 ElasticJob DataflowJob），每批只占一页内存。PUSH 队列满时按退避重试做背压
 * （{@link #injectWithBackpressure}），重试耗尽才计错，避免大批量注入瞬间打满队列被误判失败。
 */
@Component
@RequiredArgsConstructor
public class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);
    private static final int ERROR_SUMMARY_MAX = 2000;
    /** 背压：单主体注入遇队列满的最大重试次数。 */
    private static final int INJECT_MAX_RETRY = 20;
    /** 背压：每次重试前的退避毫秒。 */
    private static final long INJECT_BACKOFF_MS = 50;

    private final SubjectQueryRunner subjectQueryRunner;
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

        // counters[0]=subjectCount, [1]=success, [2]=error；sink 在 lambda 内累加，故用数组持有可变状态
        int[] counters = {0, 0, 0};
        List<String> errors = new ArrayList<>();
        String tenantId = String.valueOf(def.getTenantId());
        try {
            subjectQueryRunner.forEachTarget(def.getSubjectQuery(), target -> {
                counters[0]++;
                String subjectId = target.subjectId();
                try {
                    String eventId = EventIdHasher.hash(jobRunId, subjectId);
                    RuleEvent event = RuleEvent.builder()
                            .tenantId(tenantId)
                            .sceneCode(def.getSceneCode())
                            .eventType(def.getEventType())
                            .subjectId(subjectId)
                            .eventId(eventId)
                            .payload(target.payload())
                            .providedMetrics(target.providedMetrics())
                            .source(EventSource.JOB)
                            .build();
                    if (injectWithBackpressure(event)) {
                        counters[1]++;
                    } else {
                        counters[2]++;
                        errors.add("subjectId=" + subjectId + " 注入失败（队列持续满，重试耗尽）");
                    }
                } catch (RuntimeException e) {
                    counters[2]++;
                    errors.add("subjectId=" + subjectId + " 异常: " + e.getMessage());
                    log.warn("Job 主体注入失败 jobRunId={} subjectId={}", jobRunId, subjectId, e);
                }
            });
            exec.setStatus(counters[2] == 0 ? "SUCCESS" : (counters[1] > 0 ? "PARTIAL_FAIL" : "FAILED"));
        } catch (RuntimeException e) {
            // 主体查询阶段失败 → 整体 FAILED
            exec.setStatus("FAILED");
            errors.add("主体查询失败: " + e.getMessage());
            log.error("Job 主体查询失败 jobRunId={} jobCode={}", jobRunId, def.getCode(), e);
        }
        exec.setSubjectCount(counters[0]);
        exec.setSuccessCount(counters[1]);
        exec.setErrorCount(counters[2]);
        exec.setFinishedAt(LocalDateTime.now());
        if (!errors.isEmpty()) {
            exec.setErrorSummary(truncate(String.join("; ", errors)));
        }
        executionMapper.updateById(exec);
        return exec;
    }

    /**
     * 注入事件，PUSH 队列满时退避重试（背压）；重试耗尽仍满返回 false 计错。
     * 正常情况下首次即入队成功、不 sleep；仅队列瞬时打满时短暂退避等消费侧腾空。
     */
    private boolean injectWithBackpressure(RuleEvent event) {
        for (int attempt = 0; attempt < INJECT_MAX_RETRY; attempt++) {
            if (evalService.acceptEvent(event)) {
                return true;
            }
            try {
                Thread.sleep(INJECT_BACKOFF_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static String truncate(String s) {
        return s.length() <= ERROR_SUMMARY_MAX ? s : s.substring(0, ERROR_SUMMARY_MAX);
    }
}
