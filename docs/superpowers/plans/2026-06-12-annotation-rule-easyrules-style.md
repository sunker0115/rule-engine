# Easy Rules 风格注解规则(嵌入式 SDK)实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 SDK 注解模式加 Easy Rules 风格糖:`@Condition` 单布尔方法写条件(`@Fact`/`@Metric` 注入),命中决策后经 `DecisionFiredEvent`+`@EventListener`(甲)与 `@OnDecision`(乙)解耦执行动作。

**Architecture:** `rule-sdk` 提供纯 Java 积木(注解、`FactResolver`、`AnnotatedRuleScanner` 把 `@Condition` 包成不透明 `ConditionEvaluator` + 建快照 + 声明 metric 依赖、`DecisionDispatcher` 按 `hitDecisions` 派发);Spring 装配(bean 收集、事件桥接)全在 `rule-sdk-spring-boot-starter`。严格遵守 D60:引擎只出决策,动作在消费方进程内。

**Tech Stack:** Java 8(语法兼容现有源码)、JUnit 5 + AssertJ、Spring Boot AutoConfiguration、Maven 多模块。

**前置:** 跑 Maven 前先用 `mvn-env` skill 设环境,命令形如 `$MVN -pl <module> -am test`;跨模块改动必带 `-am`,整轮收尾用全量 `$MVN clean test`。设计依据见 `docs/superpowers/specs/2026-06-12-annotation-rule-easyrules-style-design.md` 与决策 D61。

---

## 文件结构

**rule-sdk(纯 Java,无 Spring)** — 包 `com.sstlfsj.rule.sdk`
- `annotation/Condition.java`(注:与现有 DSL 工厂类 `com.sstlfsj.rule.sdk.Condition` 同名不同包,放 `annotation` 子包避免冲突)— `@Condition` 方法注解
- `annotation/Fact.java` — `@Fact` 参数注解
- `annotation/Metric.java` — `@Metric` 参数注解
- `annotation/OnDecision.java` — `@OnDecision` 方法注解
- `DecisionFiredEvent.java` — 决策命中事件 record
- `DecisionSink.java` — 决策事件 sink 函数接口
- `DecisionContextListener.java` — 带 context 的评估回调接口
- `FactResolver.java` — `@Fact`/`@Metric` 参数注入解析
- `DecisionDispatcher.java` — 吃评估输出,按 `hitDecisions` 向 sink 派发(实现 `DecisionContextListener`)
- `source/AnnotatedRuleScanner.java` — 扫 `@Condition` POJO → 合成算子 + 快照 + metric 依赖
- 修改 `RuleEngineClient.java` — 加 `decisionContextListener`,`evaluate()` 改走 `evaluateWithContext` 并回调

**rule-sdk-spring-boot-starter(Spring)** — 包 `com.sstlfsj.rule.sdk.starter`
- `OnDecisionInvoker.java` — `@OnDecision` 方法表 + 反射调用(实现 `DecisionSink`)
- 修改 `RuleEngineClientAutoConfiguration.java` — 收集 `@RuleDef`+`@Condition` bean → scanner → client;收集 `@OnDecision` bean → invoker;装 `DecisionDispatcher`(Spring 发布 sink + invoker sink)并设为 client 的 `decisionContextListener`

**rule-samples(集成验证)** — 包 `com.sstlfsj.rule.samples.easyrules`
- `EvenNumberRule.java` — 样例规则
- `ReviewHandlers.java` — 甲 `@EventListener` + 乙 `@OnDecision`
- `test/.../EasyRulesAnnotationIT.java` — 端到端集成测试

---

## Task 1: 四个注解

**Files:**
- Create: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/annotation/Condition.java`
- Create: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/annotation/Fact.java`
- Create: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/annotation/Metric.java`
- Create: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/annotation/OnDecision.java`
- Test: `rule-sdk/src/test/java/com/sstlfsj/rule/sdk/annotation/AnnotationPresenceTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.sdk.annotation;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import static org.assertj.core.api.Assertions.assertThat;

class AnnotationPresenceTest {

    static class Sample {
        @Condition
        public boolean hit(@Fact("number") Integer n, @Metric("m") Integer m) { return true; }
        @OnDecision({"EVEN"})
        public void act(@Fact("number") Integer n) { }
    }

    @Test
    void annotations_areRuntimeVisible() throws Exception {
        Method hit = Sample.class.getMethod("hit", Integer.class, Integer.class);
        assertThat(hit.isAnnotationPresent(Condition.class)).isTrue();
        Parameter[] ps = hit.getParameters();
        assertThat(ps[0].getAnnotation(Fact.class).value()).isEqualTo("number");
        assertThat(ps[1].getAnnotation(Metric.class).value()).isEqualTo("m");
        assertThat(ps[1].getAnnotation(Metric.class).version()).isEqualTo(1);

        Method act = Sample.class.getMethod("act", Integer.class);
        assertThat(act.getAnnotation(OnDecision.class).value()).containsExactly("EVEN");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-sdk -am test -Dtest=AnnotationPresenceTest`
Expected: 编译失败(`Condition`/`Fact`/`Metric`/`OnDecision` 不存在)

- [ ] **Step 3: 写四个注解**

`Condition.java`:
```java
package com.sstlfsj.rule.sdk.annotation;

import java.lang.annotation.*;

/** 标在规则 POJO 的布尔方法上,声明该规则的条件;一个 @RuleDef 规则恰好一个。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Condition {}
```

`Fact.java`:
```java
package com.sstlfsj.rule.sdk.annotation;

import java.lang.annotation.*;

/** 注入参数:从 event.payload 取,取不到回退元数据(eventId/tenantId/.../决策码);不涉及 metric。 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Fact {
    /** payload 字段名 / 元数据键名。 */
    String value();
}
```

`Metric.java`:
```java
package com.sstlfsj.rule.sdk.annotation;

import java.lang.annotation.*;

/**
 * 注入 metric 值。标在 @Condition 参数=声明依赖(驱动预拉)+取值;标在 @OnDecision 参数=仅取值(同 context 查,查不到 null)。
 * version 在嵌入式 SDK 恒为 1,无需填写。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Metric {
    /** metric 编码。 */
    String value();
    /** metric 版本,SDK 默认 1。 */
    int version() default 1;
}
```

`OnDecision.java`:
```java
package com.sstlfsj.rule.sdk.annotation;

import java.lang.annotation.*;

/** 标在处理器方法上,按 decision code 订阅;命中对应决策时被调用,参数支持 @Fact/@Metric 注入。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OnDecision {
    /** 订阅的 decision code 列表。 */
    String[] value();
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-sdk -am test -Dtest=AnnotationPresenceTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add rule-sdk/src/main/java/com/sstlfsj/rule/sdk/annotation/ rule-sdk/src/test/java/com/sstlfsj/rule/sdk/annotation/AnnotationPresenceTest.java
git commit -m "feat(sdk): add @Condition/@Fact/@Metric/@OnDecision annotations"
```

---

## Task 2: DecisionFiredEvent + DecisionSink

**Files:**
- Create: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/DecisionFiredEvent.java`
- Create: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/DecisionSink.java`
- Test: `rule-sdk/src/test/java/com/sstlfsj/rule/sdk/DecisionFiredEventTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.sdk;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DecisionFiredEventTest {
    @Test
    void decision_matchesByCode() {
        DecisionFiredEvent e = new DecisionFiredEvent("REVIEW", 50, null, null, null);
        assertThat(e.decision("REVIEW")).isTrue();
        assertThat(e.decision("REJECT")).isFalse();
        assertThat(e.priority()).isEqualTo(50);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-sdk -am test -Dtest=DecisionFiredEventTest`
Expected: 编译失败(`DecisionFiredEvent` 不存在)

- [ ] **Step 3: 写 record + sink**

`DecisionFiredEvent.java`:
```java
package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;

/** 一个决策命中的事件,携带评估事件与上下文(供动作侧取 payload/metric/元数据)。 */
public record DecisionFiredEvent(String decisionCode, int priority, String category,
                                 RuleEvent event, EvalContext context) {
    /** 决策码是否等于 code。 */
    public boolean decision(String code) { return decisionCode.equals(code); }
}
```

`DecisionSink.java`:
```java
package com.sstlfsj.rule.sdk;

/** 决策命中事件的消费端;DecisionDispatcher 对每个命中决策回调一次。 */
@FunctionalInterface
public interface DecisionSink {
    /** 消费一个决策命中事件。实现自行处理异常隔离;DecisionDispatcher 也会在 sink 外层兜底吞异常。 */
    void accept(DecisionFiredEvent event);
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-sdk -am test -Dtest=DecisionFiredEventTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add rule-sdk/src/main/java/com/sstlfsj/rule/sdk/DecisionFiredEvent.java rule-sdk/src/main/java/com/sstlfsj/rule/sdk/DecisionSink.java rule-sdk/src/test/java/com/sstlfsj/rule/sdk/DecisionFiredEventTest.java
git commit -m "feat(sdk): add DecisionFiredEvent and DecisionSink"
```

---

## Task 3: FactResolver

**Files:**
- Create: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/FactResolver.java`
- Test: `rule-sdk/src/test/java/com/sstlfsj/rule/sdk/FactResolverTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.sdk.annotation.Fact;
import com.sstlfsj.rule.sdk.annotation.Metric;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FactResolverTest {

    static class Holder {
        public void m(@Fact("number") Integer number,
                      @Metric("total") Integer total,
                      @Fact("eventId") String eventId,
                      @Fact("missing") String missing) {}
    }

    private EvalContext ctx(Map<String, Object> payload, Map<String, MetricValue> metrics) {
        RuleEvent e = RuleEvent.builder().tenantId("t").sceneCode("s").eventType("evt")
                .subjectId("u1").eventId("evt-1").occurredAt(Instant.now())
                .payload(payload).source(EventSource.SDK).build();
        return new EvalContext("t", e, null, metrics, Instant.now());
    }

    @Test
    void resolves_payload_metric_metadata_andNullForMissing() throws Exception {
        Method m = Holder.class.getMethod("m", Integer.class, Integer.class, String.class, String.class);
        EvalContext ctx = ctx(
                Map.of("number", 7),
                Map.of("total", new MetricValue(42, "INT", "FETCHED")));

        Object[] args = new FactResolver().resolve(m.getParameters(), ctx, null);

        assertThat(args[0]).isEqualTo(7);        // payload
        assertThat(args[1]).isEqualTo(42);       // metric
        assertThat(args[2]).isEqualTo("evt-1");  // 元数据 eventId
        assertThat(args[3]).isNull();            // 全落空
    }

    @Test
    void metric_errorValue_injectsNull() throws Exception {
        Method m = Holder.class.getMethod("m", Integer.class, Integer.class, String.class, String.class);
        EvalContext ctx = ctx(Map.of("number", 1), Map.of("total", MetricValue.error("METRIC_FETCH_FAIL")));
        Object[] args = new FactResolver().resolve(m.getParameters(), ctx, null);
        assertThat(args[1]).isNull();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-sdk -am test -Dtest=FactResolverTest`
Expected: 编译失败(`FactResolver` 不存在)

- [ ] **Step 3: 写 FactResolver**

```java
package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.sdk.annotation.Fact;
import com.sstlfsj.rule.sdk.annotation.Metric;

import java.lang.reflect.Parameter;
import java.math.BigDecimal;

/**
 * 把方法参数解析成注入值。
 * @Metric:从 EvalContext 取 metric(失败/缺失=null)。
 * @Fact:先 payload,再元数据(eventId/tenantId/sceneCode/eventType/subjectId/occurredAt + 决策码/priority/category),都无=null。
 */
public final class FactResolver {

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

    private Object resolveOne(Parameter p, EvalContext ctx, DecisionFiredEvent fired) {
        Metric metric = p.getAnnotation(Metric.class);
        if (metric != null) {
            MetricValue mv = ctx == null ? null : ctx.getMetric(metric.value());
            if (mv == null || mv.isError()) return null;
            return coerce(mv.value(), p.getType());
        }
        Fact fact = p.getAnnotation(Fact.class);
        if (fact == null) {
            throw new IllegalStateException(
                    "@Condition/@OnDecision 参数必须标注 @Fact 或 @Metric: " + p);
        }
        String name = fact.value();
        RuleEvent event = ctx == null ? null : ctx.event();
        if (event != null && event.payload().containsKey(name)) {
            return coerce(event.payload().get(name), p.getType());
        }
        return metadata(name, event, fired);
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
                case "decisionCode": return fired.decisionCode();
                case "priority":     return fired.priority();
                case "category":     return fired.category();
                default: break;
            }
        }
        return null;
    }

    private static Object coerce(Object v, Class<?> t) {
        if (v == null || t.isInstance(v)) return v;
        if (v instanceof Number) {
            Number n = (Number) v;
            if (t == Integer.class || t == int.class)       return n.intValue();
            if (t == Long.class    || t == long.class)      return n.longValue();
            if (t == Double.class  || t == double.class)    return n.doubleValue();
            if (t == BigDecimal.class)                      return new BigDecimal(n.toString());
        }
        return v;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-sdk -am test -Dtest=FactResolverTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add rule-sdk/src/main/java/com/sstlfsj/rule/sdk/FactResolver.java rule-sdk/src/test/java/com/sstlfsj/rule/sdk/FactResolverTest.java
git commit -m "feat(sdk): add FactResolver for @Fact/@Metric injection"
```

---

## Task 4: AnnotatedRuleScanner

**Files:**
- Create: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/source/AnnotatedRuleScanner.java`
- Test: `rule-sdk/src/test/java/com/sstlfsj/rule/sdk/source/AnnotatedRuleScannerTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.kernel.api.annotation.DecisionBinding;
import com.sstlfsj.rule.kernel.api.annotation.RuleDef;
import com.sstlfsj.rule.kernel.api.model.MetricDependency;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.sdk.FactResolver;
import com.sstlfsj.rule.sdk.annotation.Condition;
import com.sstlfsj.rule.sdk.annotation.Fact;
import com.sstlfsj.rule.sdk.annotation.Metric;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class AnnotatedRuleScannerTest {

    @RuleDef(code = "even", sceneCode = "demo",
            decisions = @DecisionBinding(code = "EVEN", priority = 1))
    static class EvenRule {
        @Condition
        public boolean isEven(@Fact("number") Integer n, @Metric("total") Integer total) {
            return n % 2 == 0;
        }
    }

    @RuleDef(code = "bad", sceneCode = "demo")
    static class NoConditionRule {}

    @Test
    void scan_buildsSnapshotEvaluatorAndMetricDependency() {
        AnnotatedRuleScanner.ScanResult r =
                new AnnotatedRuleScanner(new FactResolver(), "t1").scan(List.of(new EvenRule()));

        assertThat(r.snapshots()).hasSize(1);
        RuleVersionSnapshot snap = r.snapshots().get(0);
        assertThat(snap.sceneCode()).isEqualTo("demo");
        assertThat(snap.code()).isEqualTo("even");
        assertThat(snap.tenantId()).isEqualTo("t1");
        assertThat(snap.metricDependencies())
                .extracting(MetricDependency::metricCode).containsExactly("total");
        // 合成算子键 = conditionAst 叶子的 conditionType,且 evaluators 含同键
        String condType = ((com.sstlfsj.rule.kernel.api.model.ast.ConditionNode) snap.conditionAst()).conditionType();
        assertThat(r.evaluators()).containsKey(condType);
    }

    @Test
    void scan_missingCondition_throws() {
        assertThatThrownBy(() ->
                new AnnotatedRuleScanner(new FactResolver(), "t1").scan(List.of(new NoConditionRule())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("@Condition");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-sdk -am test -Dtest=AnnotatedRuleScannerTest`
Expected: 编译失败(`AnnotatedRuleScanner` 不存在)

- [ ] **Step 3: 写 AnnotatedRuleScanner**

```java
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
```

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-sdk -am test -Dtest=AnnotatedRuleScannerTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add rule-sdk/src/main/java/com/sstlfsj/rule/sdk/source/AnnotatedRuleScanner.java rule-sdk/src/test/java/com/sstlfsj/rule/sdk/source/AnnotatedRuleScannerTest.java
git commit -m "feat(sdk): add AnnotatedRuleScanner wrapping @Condition into opaque evaluator"
```

---

## Task 5: DecisionDispatcher + DecisionContextListener

**Files:**
- Create: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/DecisionContextListener.java`
- Create: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/DecisionDispatcher.java`
- Test: `rule-sdk/src/test/java/com/sstlfsj/rule/sdk/DecisionDispatcherTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.Decision;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionDispatcherTest {

    private EvalResult resultWith(Decision... ds) {
        return new EvalResult(true, ds.length > 0 ? ds[0] : null, List.of(ds),
                List.of(), null, null, null, null);
    }

    @Test
    void dispatch_perHitDecision_toAllSinks_andIsolatesSinkFailure() {
        List<String> seenA = new ArrayList<>();
        DecisionSink ok = e -> seenA.add(e.decisionCode());
        DecisionSink boom = e -> { throw new RuntimeException("x"); };

        DecisionDispatcher d = new DecisionDispatcher(List.of(boom, ok));
        EvalResult r = resultWith(
                new Decision("REVIEW", "复核", 50, 1L),
                new Decision("LOG", "记录", 10, 1L));

        d.onEvaluated(null, r, null);

        // boom 抛异常被吞,不影响 ok;两个决策都派发
        assertThat(seenA).containsExactly("REVIEW", "LOG");
    }

    @Test
    void dispatch_emptyHits_noop() {
        List<String> seen = new ArrayList<>();
        DecisionDispatcher d = new DecisionDispatcher(List.of(e -> seen.add(e.decisionCode())));
        d.onEvaluated(null, EvalResult.miss(), null);
        d.onEvaluated(null, null, null);
        assertThat(seen).isEmpty();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-sdk -am test -Dtest=DecisionDispatcherTest`
Expected: 编译失败(`DecisionDispatcher` 不存在)

- [ ] **Step 3: 写接口 + 分发器**

`DecisionContextListener.java`:
```java
package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;

/** 带评估上下文的回调,用于动作派发(EvalResultListener 不带 context,无法注入 metric)。 */
@FunctionalInterface
public interface DecisionContextListener {
    /**
     * 一次评估完成后回调。
     *
     * @param event   评估事件
     * @param result  评估结果
     * @param context 评估上下文,候选为空/早返回 miss 时为 null
     */
    void onEvaluated(RuleEvent event, EvalResult result, EvalContext context);
}
```

`DecisionDispatcher.java`:
```java
package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.Decision;
import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/** 按 EvalResult.hitDecisions 顺序,为每个命中决策向所有 sink 派发一个 DecisionFiredEvent;sink 异常吞 + 记日志续跑。 */
public final class DecisionDispatcher implements DecisionContextListener {

    private static final Logger log = LoggerFactory.getLogger(DecisionDispatcher.class);

    private final List<DecisionSink> sinks;

    public DecisionDispatcher(List<DecisionSink> sinks) {
        this.sinks = List.copyOf(sinks);
    }

    @Override
    public void onEvaluated(RuleEvent event, EvalResult result, EvalContext context) {
        if (result == null || result.hitDecisions().isEmpty()) return;
        for (Decision d : result.hitDecisions()) {
            DecisionFiredEvent fired =
                    new DecisionFiredEvent(d.code(), d.priority(), d.category(), event, context);
            for (DecisionSink sink : sinks) {
                try {
                    sink.accept(fired);
                } catch (RuntimeException ex) {
                    log.error("决策 sink 处理失败,已吞:decision={} sink={}",
                            d.code(), sink.getClass().getName(), ex);
                }
            }
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-sdk -am test -Dtest=DecisionDispatcherTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add rule-sdk/src/main/java/com/sstlfsj/rule/sdk/DecisionContextListener.java rule-sdk/src/main/java/com/sstlfsj/rule/sdk/DecisionDispatcher.java rule-sdk/src/test/java/com/sstlfsj/rule/sdk/DecisionDispatcherTest.java
git commit -m "feat(sdk): add DecisionDispatcher and context-aware listener"
```

---

## Task 6: RuleEngineClient 接入 DecisionContextListener

**Files:**
- Modify: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/RuleEngineClient.java`
- Test: `rule-sdk/src/test/java/com/sstlfsj/rule/sdk/RuleEngineClientContextListenerTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RuleEngineClientContextListenerTest {

    @Test
    void evaluate_invokesContextListener_withNonNullContextOnCandidate() {
        // payloadGt amount>1000 → EVEN 决策;命中后 context 非空
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(1L).tenantId("t").sceneCode("s").code("r").version(1L)
                .conditionAst(Condition.payloadGt("amount", 1000).toAst())
                .addTriggerEventType("evt")
                .addDecisionBinding("HIT", 1)
                .build();

        AtomicReference<EvalContext> seen = new AtomicReference<>();
        try (RuleEngineClient client = RuleEngineClient.builder()
                .localSnapshot(snap)
                .decisionContextListener((e, r, c) -> seen.set(c))
                .build()) {

            RuleEvent event = RuleEvent.builder().tenantId("t").sceneCode("s").eventType("evt")
                    .subjectId("u").eventId("e1").occurredAt(Instant.now())
                    .payload(Map.of("amount", 5000)).source(EventSource.SDK).build();

            EvalResult result = client.evaluate(event);
            assertThat(result.ruleHit()).isTrue();
            assertThat(seen.get()).isNotNull();   // 带 context 回调被触发
        }
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-sdk -am test -Dtest=RuleEngineClientContextListenerTest`
Expected: 编译失败(`decisionContextListener` builder 方法不存在)

- [ ] **Step 3: 改 RuleEngineClient**

在字段区(约 `RuleEngineClient.java:51` 现有 `evalSessionListener` 后)加字段:
```java
    private final DecisionContextListener decisionContextListener;
```

在构造器赋值区(约 `:115` 现有 `this.evalSessionListener = b.evalSessionListener;` 后)加:
```java
        this.decisionContextListener = b.decisionContextListener;
```

把 `evaluate(RuleEvent)`(约 `:129-136`)整体替换为:
```java
    /** 对单个事件本地求值,零网络跳转;渠道由 SDK 入口权威设为 SDK,不信任调用方传入。 */
    public EvalResult evaluate(RuleEvent event) {
        RuleEvent sdkEvent = event.source() == EventSource.SDK
                ? event : event.toBuilder().source(EventSource.SDK).build();
        EvalOutcome outcome = evalEngine.evaluateWithContext(
                sdkEvent, evalEngine.match(sdkEvent), java.time.Instant.now());
        EvalResult result = outcome.result();
        if (evalResultListener != null) evalResultListener.onResult(sdkEvent, result);
        if (evalSessionListener != null) evalSessionListener.onSession(sdkEvent, result);
        if (decisionContextListener != null) {
            decisionContextListener.onEvaluated(sdkEvent, result, outcome.context());
        }
        return result;
    }
```

在 import 区加(若未存在):
```java
import com.sstlfsj.rule.kernel.api.model.EvalOutcome;
```

在 Builder 字段区(约 `:157` 现有 `private EvalSessionListener evalSessionListener;` 后)加:
```java
        private DecisionContextListener decisionContextListener;
```

在 Builder 方法区(现有 `evalSessionListener(...)` 方法后)加:
```java
        /** @param v 带 context 的评估回调(可选),用于注解动作派发 */
        public Builder decisionContextListener(DecisionContextListener v) {
            this.decisionContextListener = v; return this;
        }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-sdk -am test -Dtest=RuleEngineClientContextListenerTest`
Expected: PASS

- [ ] **Step 5: 跑 rule-sdk 全量回归**

Run: `$MVN -pl rule-sdk -am test`
Expected: PASS(确认 `evaluate()` 改走 `evaluateWithContext` 未破坏既有用例;此前 `evalEngine.evaluate()` 内部本就是该路径,行为等价)

- [ ] **Step 6: 提交**

```bash
git add rule-sdk/src/main/java/com/sstlfsj/rule/sdk/RuleEngineClient.java rule-sdk/src/test/java/com/sstlfsj/rule/sdk/RuleEngineClientContextListenerTest.java
git commit -m "feat(sdk): wire DecisionContextListener into RuleEngineClient.evaluate"
```

---

## Task 7: OnDecisionInvoker(starter,写法乙)

**Files:**
- Create: `rule-sdk-spring-boot-starter/src/main/java/com/sstlfsj/rule/sdk/starter/OnDecisionInvoker.java`
- Test: `rule-sdk-spring-boot-starter/src/test/java/com/sstlfsj/rule/sdk/starter/OnDecisionInvokerTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.sdk.starter;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.sdk.DecisionFiredEvent;
import com.sstlfsj.rule.sdk.FactResolver;
import com.sstlfsj.rule.sdk.annotation.Fact;
import com.sstlfsj.rule.sdk.annotation.OnDecision;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class OnDecisionInvokerTest {

    static class Handlers {
        final AtomicInteger reviewed = new AtomicInteger();
        @OnDecision("REVIEW")
        public void onReview(@Fact("number") Integer n) { reviewed.addAndGet(n); }
        @OnDecision("REVIEW")
        public void boom(@Fact("number") Integer n) { throw new RuntimeException("x"); }
    }

    private DecisionFiredEvent fired(String code) {
        RuleEvent e = RuleEvent.builder().tenantId("t").sceneCode("s").eventType("evt")
                .subjectId("u").eventId("e1").occurredAt(Instant.now())
                .payload(Map.of("number", 7)).source(EventSource.SDK).build();
        EvalContext ctx = new EvalContext("t", e, null, Map.of(), Instant.now());
        return new DecisionFiredEvent(code, 1, null, e, ctx);
    }

    @Test
    void invokes_matchingHandlers_andIsolatesHandlerFailure() {
        Handlers h = new Handlers();
        OnDecisionInvoker invoker = new OnDecisionInvoker(new FactResolver(), List.of(h));

        invoker.accept(fired("REVIEW"));   // onReview + boom 都匹配
        assertThat(h.reviewed.get()).isEqualTo(7);   // boom 抛异常被吞,onReview 仍执行

        invoker.accept(fired("OTHER"));    // 无匹配处理器 → no-op
        assertThat(h.reviewed.get()).isEqualTo(7);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-sdk-spring-boot-starter -am test -Dtest=OnDecisionInvokerTest`
Expected: 编译失败(`OnDecisionInvoker` 不存在)

- [ ] **Step 3: 写 OnDecisionInvoker**

```java
package com.sstlfsj.rule.sdk.starter;

import com.sstlfsj.rule.sdk.DecisionFiredEvent;
import com.sstlfsj.rule.sdk.DecisionSink;
import com.sstlfsj.rule.sdk.FactResolver;
import com.sstlfsj.rule.sdk.annotation.OnDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 写法乙:把 @OnDecision 方法按 decision code 建表,命中时 FactResolver 注入参数后反射调用;单处理器异常吞 + 续跑。 */
public final class OnDecisionInvoker implements DecisionSink {

    private static final Logger log = LoggerFactory.getLogger(OnDecisionInvoker.class);

    private record Handler(Object bean, Method method) {}

    private final FactResolver factResolver;
    private final Map<String, List<Handler>> byCode = new HashMap<>();

    public OnDecisionInvoker(FactResolver factResolver, List<?> handlerBeans) {
        this.factResolver = factResolver;
        for (Object bean : handlerBeans) {
            for (Method m : bean.getClass().getMethods()) {
                OnDecision ann = m.getAnnotation(OnDecision.class);
                if (ann == null) continue;
                m.setAccessible(true);
                for (String code : ann.value()) {
                    byCode.computeIfAbsent(code, k -> new ArrayList<>()).add(new Handler(bean, m));
                }
            }
        }
    }

    /** @return 是否登记了订阅 code 的处理器(供 starter 启动期 warn 用)。 */
    public boolean hasHandlerFor(String code) { return byCode.containsKey(code); }

    @Override
    public void accept(DecisionFiredEvent event) {
        List<Handler> handlers = byCode.get(event.decisionCode());
        if (handlers == null) return;
        for (Handler h : handlers) {
            try {
                Object[] args = factResolver.resolve(h.method().getParameters(), event.context(), event);
                h.method().invoke(h.bean(), args);
            } catch (Exception ex) {
                log.error("@OnDecision 处理器执行失败,已吞:decision={} handler={}#{}",
                        event.decisionCode(), h.bean().getClass().getName(), h.method().getName(), ex);
            }
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-sdk-spring-boot-starter -am test -Dtest=OnDecisionInvokerTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add rule-sdk-spring-boot-starter/src/main/java/com/sstlfsj/rule/sdk/starter/OnDecisionInvoker.java rule-sdk-spring-boot-starter/src/test/java/com/sstlfsj/rule/sdk/starter/OnDecisionInvokerTest.java
git commit -m "feat(starter): add OnDecisionInvoker for @OnDecision handlers"
```

---

## Task 8: 自动装配 — 收集 bean、装 dispatcher、桥接 Spring 事件

**Files:**
- Modify: `rule-sdk-spring-boot-starter/src/main/java/com/sstlfsj/rule/sdk/starter/RuleEngineClientAutoConfiguration.java`
- Test: `rule-sdk-spring-boot-starter/src/test/java/com/sstlfsj/rule/sdk/starter/AnnotatedRuleWiringTest.java`

- [ ] **Step 1: 写失败测试(Spring 上下文切片)**

```java
package com.sstlfsj.rule.sdk.starter;

import com.sstlfsj.rule.kernel.api.annotation.DecisionBinding;
import com.sstlfsj.rule.kernel.api.annotation.RuleDef;
import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.sdk.DecisionFiredEvent;
import com.sstlfsj.rule.sdk.RuleEngineClient;
import com.sstlfsj.rule.sdk.annotation.Condition;
import com.sstlfsj.rule.sdk.annotation.Fact;
import com.sstlfsj.rule.sdk.annotation.OnDecision;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AnnotatedRuleWiringTest {

    static final AtomicInteger VIA_EVENT = new AtomicInteger();
    static final AtomicInteger VIA_ONDECISION = new AtomicInteger();

    @RuleDef(code = "even", sceneCode = "demo", trigger = "num",
            decisions = @DecisionBinding(code = "EVEN", priority = 1))
    static class EvenRule {
        @Condition
        public boolean isEven(@Fact("number") Integer n) { return n % 2 == 0; }
    }

    @Configuration
    static class Beans {
        @Bean EvenRule evenRule() { return new EvenRule(); }
        @Bean Handlers handlers() { return new Handlers(); }
    }

    static class Handlers {
        @OnDecision("EVEN")
        public void onEven(@Fact("number") Integer n) { VIA_ONDECISION.addAndGet(n); }
        @EventListener
        public void onAny(DecisionFiredEvent e) { if (e.decision("EVEN")) VIA_EVENT.incrementAndGet(); }
    }

    @Test
    void annotatedRule_firesBothSinks() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RuleEngineClientAutoConfiguration.class))
                .withUserConfiguration(Beans.class)
                .run(ctx -> {
                    RuleEngineClient client = ctx.getBean(RuleEngineClient.class);
                    RuleEvent event = RuleEvent.builder().tenantId("").sceneCode("demo").eventType("num")
                            .subjectId("u").eventId("e1").occurredAt(Instant.now())
                            .payload(Map.of("number", 4)).source(EventSource.SDK).build();

                    EvalResult r = client.evaluate(event);
                    assertThat(r.ruleHit()).isTrue();
                    assertThat(VIA_EVENT.get()).isEqualTo(1);       // 甲:@EventListener
                    assertThat(VIA_ONDECISION.get()).isEqualTo(4);  // 乙:@OnDecision 注入 number
                });
    }
}
```

> 注:`tenantId("")` 与 `@RuleDef.tenantId() default ""` + scanner 的 `defaultTenantId`(取 `props.getTenantId()`,测试中为 null→"")一致,保证索引坐标对得上。

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-sdk-spring-boot-starter -am test -Dtest=AnnotatedRuleWiringTest`
Expected: FAIL(注解规则未装配,`VIA_EVENT`/`VIA_ONDECISION` 仍为 0)

- [ ] **Step 3: 改 AutoConfiguration**

在 `ruleEngineClient(...)` 方法签名加一个参数 `org.springframework.context.ApplicationEventPublisher eventPublisher`(放在 `ApplicationContext ctx` 后):
```java
            ApplicationContext ctx,
            org.springframework.context.ApplicationEventPublisher eventPublisher,
```

在方法体 `return builder.build();` **之前**,插入注解规则 + 动作派发装配(紧接现有 `@RuleDef / InlineRuleSpec` 装载块之后):
```java
        // 注解规则(@RuleDef + @Condition 方法)装配:扫描 → 合成算子 + 快照
        com.sstlfsj.rule.sdk.FactResolver factResolver = new com.sstlfsj.rule.sdk.FactResolver();
        List<Object> annotatedRuleBeans = new ArrayList<>();
        ctx.getBeansWithAnnotation(com.sstlfsj.rule.kernel.api.annotation.RuleDef.class)
           .forEach((name, bean) -> {
               for (java.lang.reflect.Method m : bean.getClass().getMethods()) {
                   if (m.isAnnotationPresent(com.sstlfsj.rule.sdk.annotation.Condition.class)) {
                       annotatedRuleBeans.add(bean);
                       break;
                   }
               }
           });
        if (!annotatedRuleBeans.isEmpty()) {
            com.sstlfsj.rule.sdk.source.AnnotatedRuleScanner.ScanResult scan =
                    new com.sstlfsj.rule.sdk.source.AnnotatedRuleScanner(factResolver, props.getTenantId())
                            .scan(annotatedRuleBeans);
            scan.evaluators().forEach(builder::addEvaluator);
            builder.ruleSource(new com.sstlfsj.rule.sdk.source.DslRuleSource(scan.snapshots()));
        }

        // 动作派发:Spring 事件 sink(甲) + @OnDecision sink(乙),装进 DecisionDispatcher
        OnDecisionInvoker onDecisionInvoker = new OnDecisionInvoker(
                factResolver, new ArrayList<>(beansWithOnDecision(ctx)));
        com.sstlfsj.rule.sdk.DecisionSink springSink = eventPublisher::publishEvent;
        builder.decisionContextListener(new com.sstlfsj.rule.sdk.DecisionDispatcher(
                List.of(springSink, onDecisionInvoker)));
```

在类内加一个私有辅助方法(找出含 `@OnDecision` 方法的 bean):
```java
    private static List<Object> beansWithOnDecision(ApplicationContext ctx) {
        List<Object> result = new ArrayList<>();
        for (Object bean : ctx.getBeansOfType(Object.class).values()) {
            for (java.lang.reflect.Method m : bean.getClass().getMethods()) {
                if (m.isAnnotationPresent(com.sstlfsj.rule.sdk.annotation.OnDecision.class)) {
                    result.add(bean);
                    break;
                }
            }
        }
        return result;
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-sdk-spring-boot-starter -am test -Dtest=AnnotatedRuleWiringTest`
Expected: PASS

- [ ] **Step 5: 跑 starter 全量回归**

Run: `$MVN -pl rule-sdk-spring-boot-starter -am test`
Expected: PASS(确认新增参数 `ApplicationEventPublisher` 与既有 starter 用例兼容)

- [ ] **Step 6: 提交**

```bash
git add rule-sdk-spring-boot-starter/src/main/java/com/sstlfsj/rule/sdk/starter/RuleEngineClientAutoConfiguration.java rule-sdk-spring-boot-starter/src/test/java/com/sstlfsj/rule/sdk/starter/AnnotatedRuleWiringTest.java
git commit -m "feat(starter): auto-wire annotated rules + decision dispatch (event + @OnDecision)"
```

---

## Task 9: 样例 + 端到端集成

**Files:**
- Create: `rule-samples/src/main/java/com/sstlfsj/rule/samples/easyrules/EvenNumberRule.java`
- Create: `rule-samples/src/main/java/com/sstlfsj/rule/samples/easyrules/ReviewHandlers.java`
- Test: `rule-samples/src/test/java/com/sstlfsj/rule/samples/easyrules/EasyRulesAnnotationIT.java`

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.samples.easyrules;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.sdk.RuleEngineClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EasyRulesAnnotationIT {

    @Test
    void evenNumber_firesReviewActionsViaBothPaths() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        com.sstlfsj.rule.sdk.starter.RuleEngineClientAutoConfiguration.class))
                .withBean(EvenNumberRule.class)
                .withBean(ReviewHandlers.class)
                .run(ctx -> {
                    RuleEngineClient client = ctx.getBean(RuleEngineClient.class);
                    ReviewHandlers h = ctx.getBean(ReviewHandlers.class);

                    RuleEvent even = RuleEvent.builder().tenantId("").sceneCode("number-demo")
                            .eventType("number").subjectId("u").eventId("n-4").occurredAt(Instant.now())
                            .payload(Map.of("number", 4)).source(EventSource.SDK).build();
                    EvalResult r = client.evaluate(even);

                    assertThat(r.ruleHit()).isTrue();
                    assertThat(h.eventCount()).isEqualTo(1);     // 甲
                    assertThat(h.onDecisionSum()).isEqualTo(4);  // 乙,注入 number=4

                    RuleEvent odd = even.toBuilder().eventId("n-5")
                            .payload(Map.of("number", 5)).build();
                    EvalResult r2 = client.evaluate(odd);
                    assertThat(r2.ruleHit()).isFalse();          // 奇数不命中,动作不触发
                    assertThat(h.eventCount()).isEqualTo(1);
                });
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-samples -am test -Dtest=EasyRulesAnnotationIT`
Expected: 编译失败(`EvenNumberRule`/`ReviewHandlers` 不存在)

- [ ] **Step 3: 写样例规则 + 处理器**

`EvenNumberRule.java`:
```java
package com.sstlfsj.rule.samples.easyrules;

import com.sstlfsj.rule.kernel.api.annotation.DecisionBinding;
import com.sstlfsj.rule.kernel.api.annotation.RuleDef;
import com.sstlfsj.rule.sdk.annotation.Condition;
import com.sstlfsj.rule.sdk.annotation.Fact;

/** Easy Rules 风格样例:偶数 → EVEN 决策。条件即一个布尔方法,payload.number 经 @Fact 注入。 */
@RuleDef(code = "even-number", sceneCode = "number-demo", trigger = "number",
        decisions = @DecisionBinding(code = "EVEN", priority = 1))
public class EvenNumberRule {
    @Condition
    public boolean isEven(@Fact("number") Integer number) {
        return number % 2 == 0;
    }
}
```

`ReviewHandlers.java`:
```java
package com.sstlfsj.rule.samples.easyrules;

import com.sstlfsj.rule.sdk.DecisionFiredEvent;
import com.sstlfsj.rule.sdk.annotation.Fact;
import com.sstlfsj.rule.sdk.annotation.OnDecision;
import org.springframework.context.event.EventListener;

import java.util.concurrent.atomic.AtomicInteger;

/** 两种动作写法并存:甲 @EventListener 监听 DecisionFiredEvent;乙 @OnDecision 注入 @Fact。 */
public class ReviewHandlers {

    private final AtomicInteger eventCount = new AtomicInteger();
    private final AtomicInteger onDecisionSum = new AtomicInteger();

    /** 甲:标准 Spring 事件监听。 */
    @EventListener
    public void onDecisionFired(DecisionFiredEvent e) {
        if (e.decision("EVEN")) eventCount.incrementAndGet();
    }

    /** 乙:按 decision code 订阅 + @Fact 注入。 */
    @OnDecision("EVEN")
    public void recordEven(@Fact("number") Integer number) {
        onDecisionSum.addAndGet(number);
    }

    public int eventCount()    { return eventCount.get(); }
    public int onDecisionSum() { return onDecisionSum.get(); }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-samples -am test -Dtest=EasyRulesAnnotationIT`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add rule-samples/src/main/java/com/sstlfsj/rule/samples/easyrules/ rule-samples/src/test/java/com/sstlfsj/rule/samples/easyrules/EasyRulesAnnotationIT.java
git commit -m "test(samples): end-to-end Easy Rules annotation rule with both action paths"
```

---

## Task 10: 全量回归 + 文档状态更新

**Files:**
- Modify: `docs/superpowers/specs/2026-06-12-annotation-rule-easyrules-style-design.md`(状态行)

- [ ] **Step 1: 全量编译测试**

Run: `$MVN clean test`
Expected: 全绿(只有 `clean` 才强制重编所有 test 类,兜住跨模块过期增量编译)

- [ ] **Step 2: 更新 spec 状态行**

把文档首行 `> 状态:设计待评审` 改为 `> 状态:已实现`。

- [ ] **Step 3: 提交**

```bash
git add docs/superpowers/specs/2026-06-12-annotation-rule-easyrules-style-design.md
git commit -m "docs: mark Easy Rules annotation design as implemented"
```

---

## 自查清单(已核)

- **spec 覆盖**:注解(T1)/`DecisionFiredEvent`(T2)/`FactResolver`+`@Fact`+`@Metric`两角色(T3)/`AnnotatedRuleScanner`+metric 依赖声明(T4)/`DecisionDispatcher`+错误隔离(T5)/`fire` 入口即 `evaluateWithContext`(T6)/写法乙 `@OnDecision`(T7)/写法甲 `@EventListener`+装配(T8)/端到端样例(T9)。spec §4–§8 各点均有对应 task。
- **类型一致**:`DecisionFiredEvent(decisionCode,priority,category,event,context)`、`FactResolver.resolve(Parameter[],EvalContext,DecisionFiredEvent)`、`AnnotatedRuleScanner.ScanResult(evaluators,snapshots)`、`DecisionDispatcher implements DecisionContextListener`、`OnDecisionInvoker implements DecisionSink` 跨 task 一致。
- **API 真实性**:`Condition.of(type,Map).toAst()`、`RuleVersionSnapshot.builder().addMetricDependency/addDecisionBinding/addTriggerEventType`、`EvalEngine.match/evaluateWithContext`、`EvalOutcome.context()`、`EvalContext.getMetric()`、`MetricValue.value()/isError()`、`Decision.code()/priority()/category()`、`EvalResult.hitDecisions()` 均经源码核对存在。
- **§6 边界**:`@OnDecision` 侧 `@Metric` 仅取值(`FactResolver` 对 `@Metric` 只读 context、不声明依赖,T3 实现保证)。

---

## 已知未覆盖(留作后续,不在本计划)

- **端到端 metric 预拉链路**:本计划单测只验证 `AnnotatedRuleScanner` 把 `@Metric` 声明进 `metricDependencies`(T4),未起真实 `MetricSourceHandler` 跑"声明→预拉→注入"全链路 —— 该链路复用引擎既有 metric 机制(已有测试覆盖),消费方需自备 handler + metric 定义。如需端到端验证,另起一个带 stub `MetricSourceHandler` 的样例。
- **`@OnDecision` 异步**:甲(`@EventListener`)可由消费方加 `@Async`/`@Order`;乙(`@OnDecision`)当前同步执行,如需异步另议。
