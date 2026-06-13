package com.sstlfsj.rule.kernel.api.model;

import java.util.Map;
import java.time.Instant;

/** 一次规则评估的不可变上下文，包含事件、主体、已预拉的指标快照，以及评估时刻 now。 */
public final class EvalContext {
    private final String tenantId;
    private final RuleEvent event;
    private final Subject subject;
    private final Map<String, MetricValue> metrics;
    /** 求值环境(ambient 配置)：now + scene 默认参数等;访问器委托此对象。 */
    private final EvalEnv env;

    public EvalContext(String tenantId, RuleEvent event, Subject subject,
                       Map<String, MetricValue> metrics, EvalEnv env) {
        this.tenantId = tenantId;
        this.event = event;
        this.subject = subject;
        // 防御性拷贝：保证 metrics 快照不可变，外部修改原 map 不影响上下文
        this.metrics = Map.copyOf(metrics);
        this.env = env != null ? env : new EvalEnv(null, Map.of());
    }

    /** 兼容构造器：仅 now、无 scene 默认参数（SDK 本地模式 / 测试），默认空 map。 */
    public EvalContext(String tenantId, RuleEvent event,
                       Subject subject, Map<String, MetricValue> metrics, Instant now) {
        this(tenantId, event, subject, metrics, new EvalEnv(now, Map.of()));
    }

    /** 租户标识。 */
    public String tenantId()  { return tenantId; }
    /** 触发事件。 */
    public RuleEvent event()  { return event; }
    /** 评估主体。 */
    public Subject subject()  { return subject; }
    /** 返回全量 metrics 快照（不可变视图）。 */
    public Map<String, MetricValue> metrics() { return metrics; }
    /** 返回本次评估的统一时刻（委托 env）。 */
    public Instant now()      { return env.now(); }
    /** 场景默认参数（scene.default_params 快照，ambient 配置）；键见 SceneDefaultParams（委托 env）。 */
    public Map<String, Object> sceneDefaultParams() { return env.sceneDefaultParams(); }
    /** 求值环境（ambient 配置）载体。 */
    public EvalEnv env()      { return env; }

    /** 返回已预拉的指标值，不存在时返回 null。 */
    public MetricValue getMetric(String metricCode) {
        return metrics.get(metricCode);
    }

    public boolean hasMetric(String metricCode) {
        return metrics.containsKey(metricCode);
    }
}
