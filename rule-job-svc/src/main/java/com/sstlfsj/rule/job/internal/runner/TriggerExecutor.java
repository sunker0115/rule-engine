package com.sstlfsj.rule.job.internal.runner;

import com.sstlfsj.rule.eval.api.service.EvalService;
import com.sstlfsj.rule.job.api.TaskExecutionStatus;
import com.sstlfsj.rule.job.api.TaskExecutor;
import com.sstlfsj.rule.job.api.TaskRunResult;
import com.sstlfsj.rule.job.api.TaskType;
import com.sstlfsj.rule.job.api.TriggerConfig;
import com.sstlfsj.rule.job.internal.subject.SubjectQueryRunner;
import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * TRIGGER 任务执行器：取主体 → 合成 RuleEvent → {@link EvalService#acceptEvent} 注入（D11）。
 *
 * <p>下游 Matcher / AST 对 TRIGGER 任务无感。{@code acceptEvent} 为异步 PUSH，成功入队即计入
 * successCount，不等待评估结果。PUSH 队列满时按退避重试做背压（{@link #injectWithBackpressure}），
 * 重试耗尽才计错。
 *
 * <p>读 typed {@link TriggerConfig} 取主体查询 / 场景 / 事件类型，返回 {@link TaskRunResult}；
 * 执行记录由 {@code ScheduledTaskScheduleManager} 落库。
 */
@Slf4j
@Component
public class TriggerExecutor implements TaskExecutor<TriggerConfig> {

    private static final int ERROR_SUMMARY_MAX = 2000;
    /** 背压：单主体注入遇队列满的最大重试次数。 */
    private static final int DEFAULT_INJECT_MAX_RETRY = 20;
    /** 背压：每次重试前的退避毫秒。 */
    private static final long DEFAULT_INJECT_BACKOFF_MS = 50;

    private final SubjectQueryRunner subjectQueryRunner;
    private final EvalService evalService;
    private final int injectMaxRetry;
    private final long injectBackoffMs;

    /**
     * Spring 主构造：默认背压参数（20 次 / 50ms）。
     *
     * @param subjectQueryRunner 主体查询执行器
     * @param evalService        评估注入入口
     */
    @Autowired
    public TriggerExecutor(SubjectQueryRunner subjectQueryRunner, EvalService evalService) {
        this(subjectQueryRunner, evalService, DEFAULT_INJECT_MAX_RETRY, DEFAULT_INJECT_BACKOFF_MS);
    }

    /**
     * 测试用：可注入背压参数。
     *
     * @param injectMaxRetry  注入最大重试次数
     * @param injectBackoffMs 每次重试退避毫秒
     */
    public TriggerExecutor(SubjectQueryRunner subjectQueryRunner, EvalService evalService,
                           int injectMaxRetry, long injectBackoffMs) {
        this.subjectQueryRunner = subjectQueryRunner;
        this.evalService = evalService;
        this.injectMaxRetry = injectMaxRetry;
        this.injectBackoffMs = injectBackoffMs;
    }

    @Override
    public TaskType type() {
        return TaskType.TRIGGER;
    }

    @Override
    public Class<TriggerConfig> configType() {
        return TriggerConfig.class;
    }

    @Override
    public TaskRunResult execute(long taskId, long tenantId, TriggerConfig config) {
        // counters[0]=processed, [1]=success, [2]=error；sink 在 lambda 内累加，故用数组持有可变状态
        int[] counters = {0, 0, 0};
        List<String> errors = new ArrayList<>();
        String tenant = String.valueOf(tenantId);
        TaskExecutionStatus status;
        try {
            subjectQueryRunner.forEachTarget(config.subjectQuery(), target -> {
                counters[0]++;
                String subjectId = target.subjectId();
                try {
                    String eventId = EventIdHasher.hash(taskId, subjectId);
                    RuleEvent event = RuleEvent.builder()
                            .tenantId(tenant)
                            .sceneCode(config.sceneCode())
                            .eventType(config.eventType())
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
                    log.warn("TRIGGER 主体注入失败 taskId={} subjectId={}", taskId, subjectId, e);
                }
            });
            status = counters[2] == 0 ? TaskExecutionStatus.SUCCESS
                    : (counters[1] > 0 ? TaskExecutionStatus.PARTIAL_FAIL : TaskExecutionStatus.FAILED);
        } catch (RuntimeException e) {
            // 主体查询阶段失败 → 整体 FAILED
            status = TaskExecutionStatus.FAILED;
            errors.add("主体查询失败: " + e.getMessage());
            log.warn("TRIGGER 主体查询失败 taskId={}", taskId, e);
        }
        String summary = errors.isEmpty() ? null : truncate(String.join("; ", errors));
        return new TaskRunResult(status, counters[0], counters[1], counters[2], summary);
    }

    /**
     * 注入事件，PUSH 队列满时退避重试（背压）；重试耗尽仍满返回 false 计错。
     * 正常情况下首次即入队成功、不 sleep；仅队列瞬时打满时短暂退避等消费侧腾空。
     */
    private boolean injectWithBackpressure(RuleEvent event) {
        for (int attempt = 0; attempt < injectMaxRetry; attempt++) {
            if (evalService.acceptEvent(event)) {
                return true;
            }
            try {
                Thread.sleep(injectBackoffMs);
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
