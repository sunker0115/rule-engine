package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.MetricQuery;
import com.sstlfsj.rule.sdk.annotation.Fact;
import com.sstlfsj.rule.sdk.annotation.Metric;

import java.lang.reflect.Parameter;

/**
 * 解析 @MetricSource 方法参数(取数阶段,数据源是 MetricQuery 而非 EvalContext)。
 * 逐参数确定性解析:MetricQuery 类型→原始 query;@Fact→具名值(subjectId/tenantId/metricCode/now 元数据 + eventPayload 字段);
 * @Metric→禁(metric 方法内不可依赖 metric);其余→禁。
 */
public final class MetricQueryResolver {

    /** 解析整组参数。 */
    public Object[] resolve(Parameter[] params, MetricQuery query) {
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            args[i] = resolveOne(params[i], query);
        }
        return args;
    }

    private Object resolveOne(Parameter p, MetricQuery query) {
        if (p.getType() == MetricQuery.class) {
            return query;
        }
        Fact fact = p.getAnnotation(Fact.class);
        if (fact == null) {
            throw new IllegalStateException("@MetricSource 参数须标 @Fact 或为 MetricQuery 类型: " + p);
        }
        String name = FactResolver.factName(p, fact);
        Object v = named(name, query);
        return FactResolver.coerce(v, p.getType());
    }

    private static Object named(String name, MetricQuery q) {
        switch (name) {
            case "subjectId":  return q.subjectId();
            case "tenantId":   return q.tenantId();
            case "metricCode": return q.metricCode();
            case "now":        return q.now();
            default: break;
        }
        return q.eventPayload() == null ? null : q.eventPayload().get(name);
    }

    /** 扫描期校验:MetricQuery 参数不得再标 @Fact;@Metric 禁用;非 MetricQuery 须标 @Fact。 */
    public void validate(Parameter[] params) {
        for (Parameter p : params) {
            if (p.getType() == MetricQuery.class) {
                if (p.isAnnotationPresent(Fact.class)) {
                    throw new IllegalStateException("MetricQuery 参数不得再标 @Fact: " + p);
                }
                continue;
            }
            if (p.isAnnotationPresent(Metric.class)) {
                throw new IllegalStateException("@MetricSource 参数不可用 @Metric(metric 方法内不可依赖 metric): " + p);
            }
            if (!p.isAnnotationPresent(Fact.class)) {
                throw new IllegalStateException("@MetricSource 参数须标 @Fact 或为 MetricQuery 类型: " + p);
            }
        }
    }
}
