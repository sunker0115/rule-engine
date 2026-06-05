package com.sstlfsj.rule.kernel.api.model;

import java.util.Map;
import java.time.Instant;

/** 一次规则评估的不可变上下文，包含事件、主体、已预拉的指标快照，以及评估时刻 now。 */
public final class EvalContext {
    private final String tenantId;
    private final RuleEvent event;
    private final Subject subject;
    private final Map<String, MetricValue> metrics;
    /** 引擎在评估入口注入一次，整棵 AST 共用；必填，调用方保证非 null。 */
    private final Instant now;

    public EvalContext(String tenantId, RuleEvent event,
                       Subject subject, Map<String, MetricValue> metrics, Instant now) {
        this.tenantId = tenantId;
        this.event = event;
        this.subject = subject;
        this.metrics = Map.copyOf(metrics);
        this.now = now;
    }

    public String getTenantId()  { return tenantId; }
    public RuleEvent getEvent()  { return event; }
    public Subject getSubject()  { return subject; }

    /** record 风格 accessor，与 getSubject() 等价。 */
    public Subject subject()     { return subject; }
    /** 返回全量 metrics 快照（不可变视图）。 */
    public Map<String, MetricValue> metrics() { return metrics; }

    /** 返回本次评估的统一时刻。 */
    public Instant getNow() { return now; }
    /** record 风格 accessor，与 getNow() 等价。 */
    public Instant now()    { return now; }

    /** 返回已预拉的指标值，不存在时返回 null。 */
    public MetricValue getMetric(String metricCode) {
        return metrics.get(metricCode);
    }

    public boolean hasMetric(String metricCode) {
        return metrics.containsKey(metricCode);
    }
}
