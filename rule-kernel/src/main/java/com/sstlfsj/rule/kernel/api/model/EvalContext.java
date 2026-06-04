package com.sstlfsj.rule.kernel.api.model;

import java.util.Map;

/** 一次规则评估的不可变上下文，包含事件、主体和已预拉的指标快照。 */
public final class EvalContext {
    private final String tenantId;
    private final RuleEvent event;
    private final Subject subject;
    private final Map<String, MetricValue> metrics;

    public EvalContext(String tenantId, RuleEvent event,
                       Subject subject, Map<String, MetricValue> metrics) {
        this.tenantId = tenantId;
        this.event = event;
        this.subject = subject;
        this.metrics = Map.copyOf(metrics);
    }

    public String getTenantId()  { return tenantId; }
    public RuleEvent getEvent()  { return event; }
    public Subject getSubject()  { return subject; }

    /** record 风格 accessor，与 getSubject() 等价。 */
    public Subject subject()     { return subject; }
    /** 返回全量 metrics 快照（不可变视图）。 */
    public Map<String, MetricValue> metrics() { return metrics; }

    /** 返回已预拉的指标值，不存在时返回 null。 */
    public MetricValue getMetric(String metricCode) {
        return metrics.get(metricCode);
    }

    public boolean hasMetric(String metricCode) {
        return metrics.containsKey(metricCode);
    }
}
