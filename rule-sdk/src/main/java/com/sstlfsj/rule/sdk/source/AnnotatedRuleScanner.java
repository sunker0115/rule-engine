package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.kernel.api.annotation.DecisionBinding;
import com.sstlfsj.rule.kernel.api.annotation.RuleDef;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.sdk.Condition;
import com.sstlfsj.rule.sdk.FactResolver;
import com.sstlfsj.rule.sdk.annotation.Metric;
import com.sstlfsj.rule.sdk.annotation.ScoreBand;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 扫描 @RuleDef 规则 POJO,按判定原语(@Condition / @Decide / @Score 三选一)产出:
 * boolean → 合成 ConditionEvaluator(kind=AST_BOOLEAN);@Decide → decideInvocations(kind=__anno_decide);
 * @Score → scoreInvocations(kind=__anno_score)。三者快照的 conditionAst 都携带坐标键供执行器反查。
 */
public final class AnnotatedRuleScanner {

    /** @Decide 合成执行器对应的 SDK 本地 kind 标识。 */
    public static final String KIND_DECIDE = "__anno_decide";
    /** @Score 合成执行器对应的 SDK 本地 kind 标识。 */
    public static final String KIND_SCORE  = "__anno_score";

    private final FactResolver factResolver;
    private final String defaultTenantId;

    public AnnotatedRuleScanner(FactResolver factResolver, String defaultTenantId) {
        this.factResolver = factResolver;
        this.defaultTenantId = defaultTenantId == null ? "" : defaultTenantId;
    }

    /** 扫描结果:三类注册产物 + 快照列表。 */
    public record ScanResult(Map<String, ConditionEvaluator> evaluators,
                             Map<String, AnnotatedDecideExecutor.Invocation> decideInvocations,
                             Map<String, AnnotatedScoreExecutor.Invocation> scoreInvocations,
                             List<RuleVersionSnapshot> snapshots) {}

    /**
     * 扫描规则 bean 列表。未标 @RuleDef 的静默跳过;标了但缺/多判定原语的抛 IllegalStateException。
     *
     * @param ruleBeans 规则 POJO 实例
     * @return 三类注册产物 + 快照
     */
    public ScanResult scan(List<?> ruleBeans) {
        Map<String, ConditionEvaluator> evaluators = new HashMap<>();
        Map<String, AnnotatedDecideExecutor.Invocation> decideInvocations = new HashMap<>();
        Map<String, AnnotatedScoreExecutor.Invocation> scoreInvocations = new HashMap<>();
        List<RuleVersionSnapshot> snapshots = new ArrayList<>();

        for (Object bean : ruleBeans) {
            RuleDef def = bean.getClass().getAnnotation(RuleDef.class);
            if (def == null) continue;

            Method primitive = findSinglePrimitive(bean);
            String tenant = def.tenantId().isBlank() ? defaultTenantId : def.tenantId();
            String key = "__anno:" + tenant + ":" + def.sceneCode() + ":" + def.code();
            if (evaluators.containsKey(key) || decideInvocations.containsKey(key)
                    || scoreInvocations.containsKey(key)) {
                throw new IllegalStateException("注解规则坐标重复: " + key);
            }

            // D63:校验原语方法参数(@Fact/@Metric 注入声明合法)
            factResolver.validate(primitive.getParameters());

            RuleVersionSnapshot.Builder b = RuleVersionSnapshot.builder()
                    .ruleVersionId(stableId(tenant, def.sceneCode(), def.code()))
                    .tenantId(tenant)
                    .sceneCode(def.sceneCode())
                    .code(def.code())
                    .version(def.version())
                    .conditionAst(Condition.of(key, Map.of()).toAst());

            if (def.eventTypes().length == 0) {
                b.addTriggerEventType("*");
            } else {
                for (String t : def.eventTypes()) b.addTriggerEventType(t);
            }
            for (DecisionBinding d : def.decisions()) {
                b.addDecisionBinding(d.code(), d.priority());
            }
            for (Parameter p : primitive.getParameters()) {
                Metric m = p.getAnnotation(Metric.class);
                if (m != null) b.addMetricDependency(FactResolver.metricName(p, m), m.version());
            }

            // 扫描期校验:@ScoreBand 引用的决策码须 ⊆ @RuleDef.decisions(@Decide 返回码运行期产出,不在此校验)
            java.util.Set<String> declared = new java.util.HashSet<>();
            for (DecisionBinding d : def.decisions()) declared.add(d.code());
            if (primitive.isAnnotationPresent(com.sstlfsj.rule.sdk.annotation.Score.class)) {
                for (ScoreBand sb : primitive.getAnnotationsByType(ScoreBand.class)) {
                    if (!declared.contains(sb.decision())) {
                        throw new IllegalStateException("@ScoreBand 引用了未在 @RuleDef.decisions 声明的决策码: "
                                + sb.decision() + " (规则 " + bean.getClass().getName() + ")");
                    }
                }
            }

            primitive.setAccessible(true);
            if (primitive.isAnnotationPresent(com.sstlfsj.rule.sdk.annotation.Condition.class)) {
                evaluators.put(key, wrapCondition(bean, primitive));
                // kind 默认 AST_BOOLEAN(不显式 set,执行器映射用 AST_BOOLEAN)
            } else if (primitive.isAnnotationPresent(com.sstlfsj.rule.sdk.annotation.Decide.class)) {
                decideInvocations.put(key,
                        new AnnotatedDecideExecutor.Invocation(bean, primitive, factResolver));
                b.kind(KIND_DECIDE);
            } else { // @Score
                scoreInvocations.put(key,
                        new AnnotatedScoreExecutor.Invocation(bean, primitive, factResolver, bands(primitive)));
                b.kind(KIND_SCORE);
            }
            snapshots.add(b.build());
        }
        return new ScanResult(evaluators, decideInvocations, scoreInvocations, snapshots);
    }

    /** 找出唯一判定原语方法(@Condition/@Decide/@Score 三选一),0 个或多个抛错。 */
    private static Method findSinglePrimitive(Object bean) {
        Method found = null;
        for (Method m : bean.getClass().getMethods()) {
            boolean isPrimitive = m.isAnnotationPresent(com.sstlfsj.rule.sdk.annotation.Condition.class)
                    || m.isAnnotationPresent(com.sstlfsj.rule.sdk.annotation.Decide.class)
                    || m.isAnnotationPresent(com.sstlfsj.rule.sdk.annotation.Score.class);
            if (isPrimitive) {
                if (found != null) {
                    throw new IllegalStateException("规则 " + bean.getClass().getName()
                            + " 有多个判定原语(@Condition/@Decide/@Score),只允许一个");
                }
                found = m;
            }
        }
        if (found == null) {
            throw new IllegalStateException("规则 " + bean.getClass().getName()
                    + " 缺少判定原语(@Condition/@Decide/@Score)");
        }
        return found;
    }

    private static List<AnnotatedScoreExecutor.Band> bands(Method m) {
        List<AnnotatedScoreExecutor.Band> out = new ArrayList<>();
        for (ScoreBand sb : m.getAnnotationsByType(ScoreBand.class)) {
            out.add(new AnnotatedScoreExecutor.Band(sb.min(), sb.decision()));
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("@Score 方法须至少声明一个 @ScoreBand: " + m);
        }
        return out;
    }

    private ConditionEvaluator wrapCondition(Object bean, Method method) {
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
