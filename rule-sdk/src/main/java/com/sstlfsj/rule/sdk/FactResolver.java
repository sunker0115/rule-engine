package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.sdk.annotation.Fact;
import com.sstlfsj.rule.sdk.annotation.Metric;

import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.util.Map;

/**
 * 把方法参数解析成注入值。
 * @Metric:从 EvalContext 取 metric(失败/缺失=null)。
 * @Fact:先 payload,再元数据(eventId/tenantId/sceneCode/eventType/subjectId/occurredAt + 决策码/priority/category),都无=null。
 */
public final class FactResolver {

    private static final Object NOT_FOUND = new Object();

    /**
     * 解析整组参数。
     *
     * @param params 方法参数数组
     * @param ctx    评估上下文(可为 null)
     * @param fired  决策事件(动作侧传入,条件侧传 null);提供 decisionCode/priority/category 元数据
     * @return 与 params 一一对应的注入值
     */
    public Object[] resolve(Parameter[] params, EvalContext ctx, DecisionFiredEvent fired) {
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            args[i] = resolveOne(params[i], ctx, fired);
        }
        return args;
    }

    /** @Fact 名:注解 value 非空用之,否则回退参数名。 */
    public static String factName(Parameter p, Fact fact) {
        return fact.value().isEmpty() ? p.getName() : fact.value();
    }

    /** @Metric 名:注解 value 非空用之,否则回退参数名。 */
    public static String metricName(Parameter p, Metric metric) {
        return metric.value().isEmpty() ? p.getName() : metric.value();
    }

    private Object resolveOne(Parameter p, EvalContext ctx, DecisionFiredEvent fired) {
        Metric metric = p.getAnnotation(Metric.class);
        if (metric != null) {
            MetricValue mv = ctx == null ? null : ctx.getMetric(metricName(p, metric));
            if (mv == null || mv.isError()) return null;
            return coerce(mv.value(), p.getType());
        }
        Fact fact = p.getAnnotation(Fact.class);
        if (fact == null) {
            throw new IllegalStateException(
                    "@Condition/@OnDecision 参数必须标注 @Fact 或 @Metric: " + p);
        }
        String name = factName(p, fact);
        RuleEvent event = ctx == null ? null : ctx.event();
        Object fromPayload = event == null ? NOT_FOUND : lookupPayload(event.payload(), name);
        if (fromPayload != NOT_FOUND) {
            return coerce(fromPayload, p.getType());
        }
        Object meta = metadata(name, event, fired);
        if (meta != null) {
            return coerce(meta, p.getType());
        }
        if (!fact.defaultValue().isEmpty()) {
            return coerce(fact.defaultValue(), p.getType());
        }
        if (fact.required()) {
            throw new MissingFactException(name, p);
        }
        return null;
    }

    /** 在 payload 中按名取值,支持 a.b.c 逐级下钻;缺键/断链返回 NOT_FOUND(区别于"取到 null")。 */
    private static Object lookupPayload(Map<String, Object> payload, String name) {
        if (payload == null) return NOT_FOUND;
        if (name.indexOf('.') < 0) {
            return payload.containsKey(name) ? payload.get(name) : NOT_FOUND;
        }
        Object cur = payload;
        for (String seg : name.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m) || !m.containsKey(seg)) return NOT_FOUND;
            cur = m.get(seg);
        }
        return cur;
    }

    private static Object metadata(String name, RuleEvent event, DecisionFiredEvent fired) {
        if (event != null) {
            switch (name) {
                case "eventId":    return event.eventId();
                case "tenantId":   return event.tenantId();
                case "sceneCode":  return event.sceneCode();
                case "eventType":  return event.eventType();
                case "subjectId":  return event.subjectId();
                case "occurredAt": return event.occurredAt();
                default: break;
            }
        }
        if (fired != null) {
            switch (name) {
                case "decisionCode":    return fired.decisionCode();
                case "priority":        return fired.priority();
                case "category":        return fired.category();
                case "fromRuleCode":    return fired.fromRuleCode();
                case "fromRuleVersion": return fired.fromRuleVersion();
                default: break;
            }
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object coerce(Object v, Class<?> t) {
        if (v == null || t.isInstance(v)) return v;
        if (v instanceof Number n) {
            if (t == Integer.class || t == int.class)       return n.intValue();
            if (t == Long.class    || t == long.class)      return n.longValue();
            if (t == Double.class  || t == double.class)    return n.doubleValue();
            if (t == BigDecimal.class)                      return new BigDecimal(n.toString());
        }
        if (v instanceof String s) {
            try {
                if (t == Integer.class || t == int.class)     return Integer.valueOf(s);
                if (t == Long.class    || t == long.class)    return Long.valueOf(s);
                if (t == Double.class  || t == double.class)  return Double.valueOf(s);
                if (t == BigDecimal.class)                    return new BigDecimal(s);
                if (t == Boolean.class || t == boolean.class) return Boolean.valueOf(s);
                if (t == String.class)                        return s;
                if (t.isEnum())                               return Enum.valueOf((Class) t, s);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException(
                        "无法把 \"" + s + "\" 解析为 " + t.getName(), ex);
            }
        }
        return v;
    }
}
