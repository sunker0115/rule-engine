package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.kernel.api.annotation.DecisionBinding;
import com.sstlfsj.rule.kernel.api.annotation.RuleDef;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.sdk.Condition;
import com.sstlfsj.rule.sdk.FactResolver;
import com.sstlfsj.rule.sdk.annotation.Metric;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 扫描 @RuleDef + @Condition 方法的规则 POJO,产出:
 * 1) 合成 ConditionEvaluator(键 = 派生 conditionType),把 @Condition 方法包成不透明算子;
 * 2) RuleVersionSnapshot(conditionAst 指向该 conditionType,@Metric 参数声明为 metricDependency)。
 * 由 starter 装配:evaluators 经 addEvaluator 注册,snapshots 经 DslRuleSource 载入索引。
 */
public final class AnnotatedRuleScanner {

    private final FactResolver factResolver;
    private final String defaultTenantId;

    public AnnotatedRuleScanner(FactResolver factResolver, String defaultTenantId) {
        this.factResolver = factResolver;
        this.defaultTenantId = defaultTenantId == null ? "" : defaultTenantId;
    }

    /** 扫描结果:合成算子表 + 快照列表。 */
    public record ScanResult(Map<String, ConditionEvaluator> evaluators,
                             List<RuleVersionSnapshot> snapshots) {}

    /**
     * 扫描规则 bean 列表。未标 @RuleDef 的静默跳过;标了但缺/多 @Condition 的抛 IllegalStateException。
     *
     * @param ruleBeans 规则 POJO 实例
     * @return 合成算子 + 快照
     */
    public ScanResult scan(List<?> ruleBeans) {
        Map<String, ConditionEvaluator> evaluators = new HashMap<>();
        List<RuleVersionSnapshot> snapshots = new ArrayList<>();

        for (Object bean : ruleBeans) {
            RuleDef def = bean.getClass().getAnnotation(RuleDef.class);
            if (def == null) continue;

            Method condition = findSingleCondition(bean);
            String tenant = def.tenantId().isBlank() ? defaultTenantId : def.tenantId();
            String condType = "__anno:" + tenant + ":" + def.sceneCode() + ":" + def.code();
            if (evaluators.containsKey(condType)) {
                throw new IllegalStateException("注解规则坐标重复: " + condType);
            }

            evaluators.put(condType, wrap(bean, condition));

            RuleVersionSnapshot.Builder b = RuleVersionSnapshot.builder()
                    .ruleVersionId(stableId(tenant, def.sceneCode(), def.code()))
                    .tenantId(tenant)
                    .sceneCode(def.sceneCode())
                    .code(def.code())
                    .version(def.version())
                    .conditionAst(Condition.of(condType, Map.of()).toAst());

            if (def.trigger().length == 0) {
                b.addTriggerEventType("*");
            } else {
                for (String t : def.trigger()) b.addTriggerEventType(t);
            }
            for (DecisionBinding d : def.decisions()) {
                b.addDecisionBinding(d.code(), d.priority());
            }
            for (Parameter p : condition.getParameters()) {
                Metric m = p.getAnnotation(Metric.class);
                if (m != null) b.addMetricDependency(m.value(), m.version());
            }
            snapshots.add(b.build());
        }
        return new ScanResult(evaluators, snapshots);
    }

    private static Method findSingleCondition(Object bean) {
        Method found = null;
        for (Method m : bean.getClass().getMethods()) {
            if (m.isAnnotationPresent(com.sstlfsj.rule.sdk.annotation.Condition.class)) {
                if (found != null) {
                    throw new IllegalStateException(
                            "规则 " + bean.getClass().getName() + " 有多个 @Condition,只允许一个");
                }
                found = m;
            }
        }
        if (found == null) {
            throw new IllegalStateException(
                    "规则 " + bean.getClass().getName() + " 缺少 @Condition 方法");
        }
        return found;
    }

    private ConditionEvaluator wrap(Object bean, Method method) {
        method.setAccessible(true);
        return (node, ctx) -> {
            Object[] args = factResolver.resolve(method.getParameters(), ctx, null);
            try {
                return Boolean.TRUE.equals(method.invoke(bean, args));
            } catch (InvocationTargetException e) {
                // 条件方法自身抛错:转 RuntimeException 交引擎按算子异常语义处理(降级不命中 + errorCode)
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                throw new RuntimeException("规则条件求值失败: " + bean.getClass().getName(), cause);
            } catch (IllegalAccessException e) {
                throw new RuntimeException("规则条件不可访问: " + bean.getClass().getName(), e);
            }
        };
    }

    /** 由 (tenant,scene,code) 派生稳定 64-bit 版本 id,与现有 AnnotationRuleSource 同款。 */
    private static long stableId(String tenant, String scene, String code) {
        return (tenant + ":" + scene + ":" + code).hashCode() & 0xffffffffL;
    }
}
