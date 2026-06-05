package com.sstlfsj.rule.kernel.internal.context;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricSourceHandler;
import com.sstlfsj.rule.kernel.api.spi.subject.SubjectLoader;

import java.time.Instant;
import java.util.*;

/**
 * 装配 EvalContext：SubjectLoader（可选 SPI）+ providedMetrics 优先匹配 + MetricSourceHandler（可选 SPI）。
 * v1 无真实 MetricSourceHandler 实现时，仅 providedMetrics 生效。
 * v2 引入 MetricSourceHandler 实现后可在此加入 CompletableFuture.allOf 并发装配。
 */
public class EvalContextAssembler {

    /** 支持 USER 类型的第一个 SubjectLoader，优先使用；无实现时返回空 Subject。 */
    private final SubjectLoader subjectLoader;
    /** MetricSourceHandler 列表，v1 通常为空。 */
    private final List<MetricSourceHandler> metricHandlers;

    public EvalContextAssembler(List<SubjectLoader> subjectLoaders,
                                List<MetricSourceHandler> metricHandlers) {
        this.subjectLoader = subjectLoaders == null ? null :
                subjectLoaders.stream()
                        .filter(l -> l.supportedTypes().contains(SubjectType.USER))
                        .findFirst()
                        .orElse(null);
        this.metricHandlers = metricHandlers == null ? List.of() : List.copyOf(metricHandlers);
    }

    /**
     * 装配一次评估的 EvalContext。
     * <ol>
     *   <li>Subject 加载（有 SubjectLoader 时调用，否则构造空 Subject）</li>
     *   <li>providedMetrics 优先填充</li>
     *   <li>MetricSourceHandler 并发补充剩余 metric（v1 无实现则跳过）</li>
     * </ol>
     *
     * @param event      触发事件
     * @param candidates 候选 RuleVersionSnapshot（用于未来扩展，v1 不用）
     * @param now        本次评估统一时刻，由调用方（EvalEngine）注入
     * @return 不可变 EvalContext
     */
    public EvalContext assemble(RuleEvent event,
                                List<RuleVersionSnapshot> candidates,
                                Instant now) {
        Subject subject = loadSubject(event);

        // providedMetrics 转为 MetricValue Map（valueSource=PROVIDED）
        Map<String, MetricValue> metrics = new HashMap<>();
        for (Map.Entry<String, Object> entry : event.providedMetrics().entrySet()) {
            metrics.put(entry.getKey(),
                    new MetricValue(entry.getValue(), "UNKNOWN", "PROVIDED"));
        }

        return new EvalContext(event.tenantId(), event, subject, metrics, now);
    }

    private Subject loadSubject(RuleEvent event) {
        if (subjectLoader == null) {
            // 无 SubjectLoader 实现时返回最小可用 Subject
            return new Subject(event.subjectId(), SubjectType.USER, Map.of());
        }
        try {
            return subjectLoader.load(event.subjectId(), SubjectType.USER, event);
        } catch (Exception e) {
            // SubjectLoader 异常：降级返回空 Subject，不阻断评估
            return new Subject(event.subjectId(), SubjectType.USER, Map.of());
        }
    }
}
