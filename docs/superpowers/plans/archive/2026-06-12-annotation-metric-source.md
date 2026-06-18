# @MetricSource 方法式取数注解 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 metric 取数供给侧加方法式糖 `@MetricSource`,把"实现 `MetricSourceHandler` 接口 + 写 `MetricDescriptor` 定义"塌缩成一个带注解的方法;接口式 SPI 保留并补"一 handler 多 metric"样例。

**Architecture:** 新增 `@MetricSource`(方法注解)、`MetricQueryResolver`(`@Fact` over `MetricQuery` + `MetricQuery` 逃生口)、`AnnotatedMetricScanner`(从所有 bean 收集 → 合成 `MetricSourceHandler` + 自动 `MetricDescriptor`)。`RuleEngineClient.Builder` 加"按显式 sourceType 注册 handler"的路,AutoConfiguration 收集 `@MetricSource` bean。引擎取数管线与接口式 SPI 不动。设计依据见 `docs/superpowers/specs/2026-06-12-annotation-metric-source-design.md`(D65)。

**Tech Stack:** Java 25、JUnit 5 + AssertJ、Spring Boot AutoConfiguration、Maven 多模块。

**前置:** `mvn-env` skill 设 JDK 25;跨模块改动带 `-am`;收尾 `$MVN clean test`。注释中文、测试方法名英文。

---

## 关键 API(已核)

- `MetricSourceHandler.fetch(MetricQuery) → MetricValue`(SPI)。
- `MetricQuery(metricCode, tenantId, subjectId, params, eventPayload, now)`。
- `MetricValue(Object value, String dataType, String valueSource)` 3 参 + `error(code)`;`DataType.LONG/DOUBLE/DECIMAL/BOOLEAN/STRING.tag()`、`ValueSource.FETCHED.tag()`。
- `MetricDescriptor(metricCode, sourceType, dataType, allowProvided, cacheTtlSeconds, params)` 6 参便利构造。
- `FactResolver.factName(Parameter, Fact)`(public static,D63)、`coerce`(private static,本计划 T2 改包级)。
- `RuleEngineClient`:`toSourceTypeMap(List<MetricSourceHandler>)`(:133)、`metricHandlers` 字段(:185)、`localMetric(tenant, descriptor)`(:307)、assembler 装配(:59-67)。
- AutoConfiguration:`ObjectProvider<MetricSourceHandler> metricHandlers`(:59)、`metricHandlers.forEach(builder::metricSourceHandler)`(:155)、`beansWithOnDecision(ctx)` 辅助方法模板。
- `DslMetricDefinitionSource(tenantId, List<MetricDescriptor>)`、`MetricDefinitionSource`(`sdk.source`)。

---

## 文件结构

**rule-sdk**(`com.sstlfsj.rule.sdk`)
- `annotation/MetricSource.java`(新)
- `MetricQueryResolver.java`(新)— `@Fact` over MetricQuery + MetricQuery 逃生口 + 校验
- `FactResolver.java` — `coerce` 改包级可见(供同包 MetricQueryResolver 复用)
- `source/AnnotatedMetricScanner.java`(新)— 扫 `@MetricSource` → 合成 handler + 自动 descriptor + dataType 推断
- `RuleEngineClient.java` — Builder 加 `explicitSourceHandlers` + `addMetricSourceHandler`,build 合并 sourceType map

**rule-sdk-spring-boot-starter**
- `RuleEngineClientAutoConfiguration.java` — 收集 `@MetricSource` bean → scanner → 灌 handler/descriptor

**rule-samples**(`com.sstlfsj.rule.samples.metric`)
- `VelocityMetrics.java`(新,`@MetricSource`)替换 `RecentTxnCountHandler` + `MetricDemoConfig`(删)
- 改 `VelocityRuleIT` / `MetricDemoApplication` 用新 bean
- `featurestore/`(新)— 一 handler 多 metric 接口式样例:`FeatureStoreHandler` + `FeatureStoreConfig` + `AccountRiskRule` + IT

---

## Task 1: `@MetricSource` 注解

**Files:**
- Create: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/annotation/MetricSource.java`
- Test: `rule-sdk/src/test/java/com/sstlfsj/rule/sdk/annotation/MetricSourcePresenceTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.sdk.annotation;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import static org.assertj.core.api.Assertions.assertThat;

class MetricSourcePresenceTest {

    static class Holder {
        @MetricSource(value = "m", cacheTtlSeconds = 60, allowProvided = true)
        public long m() { return 1; }
    }

    @Test
    void annotation_isRuntimeVisibleWithAttributes() throws Exception {
        Method m = Holder.class.getMethod("m");
        MetricSource ann = m.getAnnotation(MetricSource.class);
        assertThat(ann.value()).isEqualTo("m");
        assertThat(ann.cacheTtlSeconds()).isEqualTo(60);
        assertThat(ann.allowProvided()).isTrue();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-sdk -am test -Dtest=MetricSourcePresenceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败(`MetricSource` 不存在)

- [ ] **Step 3: 写注解**

```java
package com.sstlfsj.rule.sdk.annotation;

import java.lang.annotation.*;

/**
 * 标在方法上声明一个 metric 的取数逻辑(供给侧),= 消费侧 @Metric 的值。
 * 扫描器把方法合成 MetricSourceHandler + 自动生成 MetricDescriptor,无需实现接口、无需单独写定义。
 * 可标在任意 Spring bean(含规则类:metric 私有于该规则时)。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MetricSource {
    /** metric 编码,(tenant 内)全局唯一。 */
    String value();
    /** 取数结果缓存 ttl 秒;0 = 不缓存。 */
    int cacheTtlSeconds() default 0;
    /** 是否允许调用方推值(providedMetrics)覆盖 fetch;默认 false=恒走本方法。 */
    boolean allowProvided() default false;
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-sdk -am test -Dtest=MetricSourcePresenceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add rule-sdk/src/main/java/com/sstlfsj/rule/sdk/annotation/MetricSource.java rule-sdk/src/test/java/com/sstlfsj/rule/sdk/annotation/MetricSourcePresenceTest.java
git commit -m "feat(sdk): add @MetricSource method annotation"
```

---

## Task 2: MetricQueryResolver(`@Fact` over MetricQuery + 逃生口 + 校验)

**Files:**
- Modify: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/FactResolver.java`(coerce 改包级)
- Create: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/MetricQueryResolver.java`
- Test: `rule-sdk/src/test/java/com/sstlfsj/rule/sdk/MetricQueryResolverTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.MetricQuery;
import com.sstlfsj.rule.sdk.annotation.Fact;
import com.sstlfsj.rule.sdk.annotation.Metric;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class MetricQueryResolverTest {

    static class Holder {
        public void inject(@Fact("subjectId") String subject,
                           @Fact("amount") Integer amount,
                           MetricQuery raw) {}
        public void badMetric(@Metric("x") Integer x) {}
        public void badUnannotated(Integer x) {}
        public void badBoth(@Fact("subjectId") MetricQuery q) {}
    }

    private MetricQuery query() {
        return new MetricQuery("recent", "t", "u-1", Map.of(),
                Map.of("amount", 8000), Instant.now());
    }

    @Test
    void resolves_fact_payload_and_rawQuery() throws Exception {
        Method m = Holder.class.getMethod("inject", String.class, Integer.class, MetricQuery.class);
        Object[] args = new MetricQueryResolver().resolve(m.getParameters(), query());
        assertThat(args[0]).isEqualTo("u-1");      // @Fact subjectId 元数据
        assertThat(args[1]).isEqualTo(8000);       // @Fact amount payload
        assertThat(args[2]).isInstanceOf(MetricQuery.class);  // 逃生口
    }

    @Test
    void validate_rejectsMetricUnannotatedAndBothTagged() throws Exception {
        MetricQueryResolver r = new MetricQueryResolver();
        assertThatThrownBy(() -> r.validate(Holder.class.getMethod("badMetric", Integer.class).getParameters()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("@Metric");
        assertThatThrownBy(() -> r.validate(Holder.class.getMethod("badUnannotated", Integer.class).getParameters()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("@Fact");
        assertThatThrownBy(() -> r.validate(Holder.class.getMethod("badBoth", MetricQuery.class).getParameters()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("MetricQuery");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-sdk -am test -Dtest=MetricQueryResolverTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败(`MetricQueryResolver` 不存在)

- [ ] **Step 3: FactResolver.coerce 改包级可见**

把 `FactResolver.java` 的 `private static Object coerce(Object v, Class<?> t)` 改为 `static Object coerce(Object v, Class<?> t)`(去掉 `private`;`@SuppressWarnings` 保留)。同包的 `MetricQueryResolver` 复用之。

- [ ] **Step 4: 写 MetricQueryResolver**

```java
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
```

- [ ] **Step 5: 跑测试确认通过 + 模块回归**

Run: `$MVN -pl rule-sdk -am test -Dtest=MetricQueryResolverTest -Dsurefire.failIfNoSpecifiedTests=false` 然后 `$MVN -pl rule-sdk -am test`
Expected: 均 PASS

- [ ] **Step 6: 提交**

```bash
git add rule-sdk/src/main/java/com/sstlfsj/rule/sdk/FactResolver.java rule-sdk/src/main/java/com/sstlfsj/rule/sdk/MetricQueryResolver.java rule-sdk/src/test/java/com/sstlfsj/rule/sdk/MetricQueryResolverTest.java
git commit -m "feat(sdk): add MetricQueryResolver for @MetricSource param injection"
```

---

## Task 3: AnnotatedMetricScanner(合成 handler + 自动 descriptor)

**Files:**
- Create: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/source/AnnotatedMetricScanner.java`
- Test: `rule-sdk/src/test/java/com/sstlfsj/rule/sdk/source/AnnotatedMetricScannerTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.kernel.api.model.MetricDescriptor;
import com.sstlfsj.rule.kernel.api.model.MetricQuery;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.sdk.MetricQueryResolver;
import com.sstlfsj.rule.sdk.annotation.Fact;
import com.sstlfsj.rule.sdk.annotation.MetricSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class AnnotatedMetricScannerTest {

    static class Metrics {
        @MetricSource(value = "recent_txn_count", cacheTtlSeconds = 60)
        public long recent(@Fact("subjectId") String subjectId) {
            return "frequent-user".equals(subjectId) ? 5 : 1;
        }
    }

    static class Dup {
        @MetricSource("recent_txn_count") public long a() { return 1; }
        @MetricSource("recent_txn_count") public long b() { return 2; }
    }

    @Test
    void scan_buildsSyntheticHandlerAndDescriptor() {
        AnnotatedMetricScanner.ScanResult r =
                new AnnotatedMetricScanner(new MetricQueryResolver(), "t1").scan(List.of(new Metrics()));

        assertThat(r.descriptors()).hasSize(1);
        MetricDescriptor d = r.descriptors().get(0);
        assertThat(d.metricCode()).isEqualTo("recent_txn_count");
        assertThat(d.sourceType()).isEqualTo("__anno_metric:recent_txn_count");
        assertThat(d.dataType()).isEqualTo("LONG");
        assertThat(d.cacheTtlSeconds()).isEqualTo(60);

        // 合成 handler 按 query 反射调方法
        var handler = r.handlers().get("__anno_metric:recent_txn_count");
        MetricValue v = handler.fetch(new MetricQuery("recent_txn_count", "t1", "frequent-user",
                Map.of(), Map.of(), Instant.now()));
        assertThat(v.value()).isEqualTo(5L);
        assertThat(v.valueSource()).isEqualTo("FETCHED");
    }

    @Test
    void scan_rejectsDuplicateMetricCode() {
        assertThatThrownBy(() ->
                new AnnotatedMetricScanner(new MetricQueryResolver(), "t1").scan(List.of(new Dup())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("recent_txn_count");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-sdk -am test -Dtest=AnnotatedMetricScannerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败(`AnnotatedMetricScanner` 不存在)

- [ ] **Step 3: 写扫描器**

```java
package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.kernel.api.model.DataType;
import com.sstlfsj.rule.kernel.api.model.MetricDescriptor;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ValueSource;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricSourceHandler;
import com.sstlfsj.rule.sdk.MetricQueryResolver;
import com.sstlfsj.rule.sdk.annotation.MetricSource;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从所有 bean 收集 @MetricSource 方法,每个产出:合成 MetricSourceHandler(键=合成 sourceType
 * __anno_metric:&lt;code&gt;)+ 自动 MetricDescriptor(dataType 由返回类型推)。由 starter 灌进 client。
 */
public final class AnnotatedMetricScanner {

    private final MetricQueryResolver resolver;
    private final String tenantId;

    public AnnotatedMetricScanner(MetricQueryResolver resolver, String tenantId) {
        this.resolver = resolver;
        this.tenantId = tenantId == null ? "" : tenantId;
    }

    /** 合成 handler 表(sourceType→handler)+ 自动 descriptor 列表(都属 tenantId)。 */
    public record ScanResult(Map<String, MetricSourceHandler> handlers,
                             List<MetricDescriptor> descriptors, String tenantId) {}

    public ScanResult scan(List<?> beans) {
        Map<String, MetricSourceHandler> handlers = new HashMap<>();
        List<MetricDescriptor> descriptors = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (Object bean : beans) {
            for (Method m : bean.getClass().getMethods()) {
                MetricSource ann = m.getAnnotation(MetricSource.class);
                if (ann == null) continue;
                String code = ann.value();
                if (code.isBlank()) {
                    throw new IllegalStateException("@MetricSource.value 不可为空: " + m);
                }
                if (!seen.add(code)) {
                    throw new IllegalStateException("@MetricSource metricCode 重复: " + code);
                }
                resolver.validate(m.getParameters());
                String dataType = dataTypeTag(m.getReturnType());
                String sourceType = "__anno_metric:" + code;
                m.setAccessible(true);
                handlers.put(sourceType, wrap(bean, m, dataType));
                descriptors.add(new MetricDescriptor(
                        code, sourceType, dataType, ann.allowProvided(), ann.cacheTtlSeconds(), Map.of()));
            }
        }
        return new ScanResult(handlers, descriptors, tenantId);
    }

    private MetricSourceHandler wrap(Object bean, Method method, String dataType) {
        return query -> {
            try {
                Object[] args = resolver.resolve(method.getParameters(), query);
                Object ret = method.invoke(bean, args);
                return new MetricValue(ret, dataType, ValueSource.FETCHED.tag());
            } catch (Exception e) {
                return MetricValue.error("METRIC_SOURCE_EVAL_ERROR");
            }
        };
    }

    /** 返回类型 → DataType tag;不可映射类型抛错。 */
    private static String dataTypeTag(Class<?> t) {
        if (t == long.class || t == Long.class || t == int.class || t == Integer.class) return DataType.LONG.tag();
        if (t == double.class || t == Double.class || t == float.class || t == Float.class) return DataType.DOUBLE.tag();
        if (t == BigDecimal.class) return DataType.DECIMAL.tag();
        if (t == boolean.class || t == Boolean.class) return DataType.BOOLEAN.tag();
        if (t == String.class) return DataType.STRING.tag();
        throw new IllegalStateException("@MetricSource 返回类型无法映射到 DataType: " + t);
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-sdk -am test -Dtest=AnnotatedMetricScannerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add rule-sdk/src/main/java/com/sstlfsj/rule/sdk/source/AnnotatedMetricScanner.java rule-sdk/src/test/java/com/sstlfsj/rule/sdk/source/AnnotatedMetricScannerTest.java
git commit -m "feat(sdk): add AnnotatedMetricScanner wrapping @MetricSource into synthetic handler"
```

---

## Task 4: RuleEngineClient.Builder 按显式 sourceType 注册 handler

**Files:**
- Modify: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/RuleEngineClient.java`
- Test: `rule-sdk/src/test/java/com/sstlfsj/rule/sdk/RuleEngineClientMetricSourceTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RuleEngineClientMetricSourceTest {

    private static RuleVersionSnapshot scoreGt80() {
        return RuleVersionSnapshot.builder()
                .ruleVersionId(1L).tenantId("t1").sceneCode("fraud")
                .conditionAst(new AndNode(List.of(
                        new ConditionNode("GT", "risk.score", null, Map.of("threshold", 80), 0.0)), null, null))
                .addTriggerEventType("TXN").addDecisionBinding("BLOCK", 100)
                .addMetricDependency("risk.score", 1).build();
    }

    @Test
    void explicitSourceHandler_andDescriptor_driveFetch() {
        MetricDescriptor def = new MetricDescriptor("risk.score", "SYN", "LONG", false, 0, Map.of());
        try (RuleEngineClient client = RuleEngineClient.builder()
                .localSnapshot(scoreGt80())
                .localMetric("t1", def)
                .addMetricSourceHandler("SYN", q -> new MetricValue(90, "LONG", "FETCHED"))
                .build()) {
            RuleEvent e = new RuleEvent("t1", "fraud", "TXN", "s1",
                    UUID.randomUUID().toString(), Instant.now(), Map.of(), Map.of(), EventSource.SDK);
            EvalResult r = client.evaluate(e);
            assertThat(r.ruleHit()).isTrue();
            assertThat(r.finalDecision().code()).isEqualTo("BLOCK");
        }
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-sdk -am test -Dtest=RuleEngineClientMetricSourceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败(`addMetricSourceHandler` 不存在)

- [ ] **Step 3: Builder 加字段 + 方法,build 合并 sourceType map**

在 Builder 字段区(`metricHandlers` 旁,约 :185)加:
```java
        private final Map<String, MetricSourceHandler> explicitSourceHandlers = new HashMap<>();
```
在 Builder 方法区(`metricSourceHandler(...)` 旁,约 :265)加:
```java
        /** 按显式 sourceType 注册 handler(供 @MetricSource 合成 handler 用;无 @MetricSourceType 注解)。 */
        public Builder addMetricSourceHandler(String sourceType, MetricSourceHandler handler) {
            explicitSourceHandlers.put(sourceType, handler); return this;
        }
```
把构造器 assembler 装配块(约 :59-71)替换为(合并两类 handler 成一个 sourceType map):
```java
        MetricDefinitionRegistry metricRegistry = new MetricDefinitionRegistry();
        Map<String, MetricSourceHandler> sourceMap = new HashMap<>(toSourceTypeMap(b.metricHandlers));
        sourceMap.putAll(b.explicitSourceHandlers);
        boolean fetchEnabled = !sourceMap.isEmpty();
        EvalContextAssembler assembler;
        if (fetchEnabled) {
            MetricDefinitionResolver resolver = b.metricDefinitionResolver != null
                    ? b.metricDefinitionResolver
                    : new SnapshotMetricDefinitionResolver(metricRegistry);
            assembler = new EvalContextAssembler(List.of(),
                    sourceMap,
                    resolver, b.metricCache, b.fetchExecutor, 0L);
        } else {
            assembler = new EvalContextAssembler(List.of(), List.of());
        }
```
> 注:原 `MetricDefinitionRegistry metricRegistry = new MetricDefinitionRegistry();`(:59)若在替换块外,确保不重复声明——以替换块内的为准,删原行。`build()` 末尾的"配了定义来源但无 handler 报错"校验(约 :334)对 `explicitSourceHandlers` 同样视为 handler:把该校验的 `metricHandlers.isEmpty()` 改为 `metricHandlers.isEmpty() && explicitSourceHandlers.isEmpty()`。

- [ ] **Step 4: 跑测试确认通过 + 模块回归**

Run: `$MVN -pl rule-sdk -am test -Dtest=RuleEngineClientMetricSourceTest -Dsurefire.failIfNoSpecifiedTests=false` 然后 `$MVN -pl rule-sdk -am test`
Expected: 均 PASS(既有 `RuleEngineClientFetchTest` 不受影响——接口式 handler 仍走 `toSourceTypeMap`)

- [ ] **Step 5: 提交**

```bash
git add rule-sdk/src/main/java/com/sstlfsj/rule/sdk/RuleEngineClient.java rule-sdk/src/test/java/com/sstlfsj/rule/sdk/RuleEngineClientMetricSourceTest.java
git commit -m "feat(sdk): Builder.addMetricSourceHandler 按显式 sourceType 注册合成 handler"
```

---

## Task 5: AutoConfiguration 收集 @MetricSource bean

**Files:**
- Modify: `rule-sdk-spring-boot-starter/src/main/java/com/sstlfsj/rule/sdk/starter/RuleEngineClientAutoConfiguration.java`
- Test: `rule-sdk-spring-boot-starter/src/test/java/com/sstlfsj/rule/sdk/starter/MetricSourceWiringTest.java`

- [ ] **Step 1: 写失败测试(Spring 切片端到端)**

```java
package com.sstlfsj.rule.sdk.starter;

import com.sstlfsj.rule.kernel.api.annotation.DecisionBinding;
import com.sstlfsj.rule.kernel.api.annotation.RuleDef;
import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.sdk.RuleEngineClient;
import com.sstlfsj.rule.sdk.annotation.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MetricSourceWiringTest {

    @RuleDef(code = "velocity", sceneCode = "risk", trigger = "txn",
            decisions = @DecisionBinding(code = "REVIEW", priority = 50))
    static class VelocityRule {
        @Condition
        public boolean suspicious(@Fact("amount") Integer amount,
                                  @Metric("recent_txn_count") Integer count) {
            return amount > 1000 && count >= 3;
        }
    }

    @Configuration
    static class Beans {
        @Bean VelocityRule rule() { return new VelocityRule(); }
        @Bean Metrics metrics() { return new Metrics(); }
    }

    static class Metrics {
        @MetricSource(value = "recent_txn_count", cacheTtlSeconds = 60)
        public long recent(@Fact("subjectId") String subjectId) {
            return "frequent-user".equals(subjectId) ? 5 : 1;
        }
    }

    @Test
    void metricSourceBean_isWired_andDrivesDecision() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RuleEngineClientAutoConfiguration.class))
                .withUserConfiguration(Beans.class)
                .run(ctx -> {
                    RuleEngineClient client = ctx.getBean(RuleEngineClient.class);
                    assertThat(client.evaluate(txn("frequent-user", 2000)).ruleHit()).isTrue();
                    assertThat(client.evaluate(txn("normal-user", 2000)).ruleHit()).isFalse();
                });
    }

    private static RuleEvent txn(String subject, int amount) {
        return RuleEvent.builder().tenantId("").sceneCode("risk").eventType("txn")
                .subjectId(subject).eventId(UUID.randomUUID().toString()).occurredAt(Instant.now())
                .payload(Map.of("amount", amount)).source(EventSource.SDK).build();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-sdk-spring-boot-starter -am test -Dtest=MetricSourceWiringTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL(@MetricSource 未装配 → fetch 不到 recent_txn_count → 命中判定错)

- [ ] **Step 3: AutoConfiguration 收集 @MetricSource bean 并灌入**

先 READ `RuleEngineClientAutoConfiguration.java` 确认结构。在 `ruleEngineClient(...)` 方法体内、注解规则装配块之后,插入 metric 供给装配:
```java
        // @MetricSource 方法式取数:扫描所有 bean → 合成 handler + 自动 descriptor
        java.util.List<Object> metricSourceBeans = beansWith(ctx,
                com.sstlfsj.rule.sdk.annotation.MetricSource.class);
        if (!metricSourceBeans.isEmpty()) {
            com.sstlfsj.rule.sdk.source.AnnotatedMetricScanner.ScanResult scan =
                    new com.sstlfsj.rule.sdk.source.AnnotatedMetricScanner(
                            new com.sstlfsj.rule.sdk.MetricQueryResolver(), props.getTenantId())
                            .scan(metricSourceBeans);
            scan.handlers().forEach(builder::addMetricSourceHandler);
            scan.descriptors().forEach(d -> builder.localMetric(scan.tenantId(), d));
        }
```
把现有 `beansWithOnDecision(ctx)` 私有辅助泛化为按注解类型查(或新增 `beansWith`),供 `@MetricSource` 复用:
```java
    private static java.util.List<Object> beansWith(ApplicationContext ctx,
            Class<? extends java.lang.annotation.Annotation> methodAnnotation) {
        java.util.List<Object> result = new java.util.ArrayList<>();
        for (Object bean : ctx.getBeansOfType(Object.class).values()) {
            for (java.lang.reflect.Method m : bean.getClass().getMethods()) {
                if (m.isAnnotationPresent(methodAnnotation)) { result.add(bean); break; }
            }
        }
        return result;
    }
```
(`beansWithOnDecision` 可改为 `beansWith(ctx, OnDecision.class)`,或保留并新增 `beansWith`——二选一,保持一处实现。)

- [ ] **Step 4: 跑测试确认通过 + starter 回归**

Run: `$MVN -pl rule-sdk-spring-boot-starter -am test -Dtest=MetricSourceWiringTest -Dsurefire.failIfNoSpecifiedTests=false` 然后 `$MVN -pl rule-sdk-spring-boot-starter -am test`
Expected: 均 PASS

- [ ] **Step 5: 提交**

```bash
git add rule-sdk-spring-boot-starter/src/main/java/com/sstlfsj/rule/sdk/starter/RuleEngineClientAutoConfiguration.java rule-sdk-spring-boot-starter/src/test/java/com/sstlfsj/rule/sdk/starter/MetricSourceWiringTest.java
git commit -m "feat(starter): auto-wire @MetricSource beans (synthetic handler + descriptor)"
```

---

## Task 6: 样例塌缩 —— `RecentTxnCountHandler`+`MetricDemoConfig` → 一个 `@MetricSource` 方法

**Files:**
- Create: `rule-samples/src/main/java/com/sstlfsj/rule/samples/metric/VelocityMetrics.java`
- Delete: `rule-samples/src/main/java/com/sstlfsj/rule/samples/metric/RecentTxnCountHandler.java`
- Delete: `rule-samples/src/main/java/com/sstlfsj/rule/samples/metric/MetricDemoConfig.java`
- Modify: `rule-samples/src/test/java/com/sstlfsj/rule/samples/metric/VelocityRuleIT.java`
- Modify: `rule-samples/src/main/java/com/sstlfsj/rule/samples/metric/MetricDemoApplication.java`(Javadoc 引用)

- [ ] **Step 1: 写新 bean `VelocityMetrics`**

```java
package com.sstlfsj.rule.samples.metric;

import com.sstlfsj.rule.sdk.annotation.Fact;
import com.sstlfsj.rule.sdk.annotation.MetricSource;
import org.springframework.stereotype.Component;

/**
 * @MetricSource 方法式取数:一个带注解的方法即"取数逻辑 + metric 定义",替代"实现 MetricSourceHandler
 * 接口 + 写 MetricDescriptor"两步。recent_txn_count 模拟按 subjectId 统计近期交易数。
 */
@Component
public class VelocityMetrics {

    @MetricSource(value = "recent_txn_count", cacheTtlSeconds = 60)
    public long recentTxnCount(@Fact("subjectId") String subjectId) {
        return "frequent-user".equals(subjectId) ? 5 : 1;
    }
}
```

- [ ] **Step 2: 删旧两类 + 改 IT/main 引用**

```bash
git rm rule-samples/src/main/java/com/sstlfsj/rule/samples/metric/RecentTxnCountHandler.java rule-samples/src/main/java/com/sstlfsj/rule/samples/metric/MetricDemoConfig.java
```
`VelocityRuleIT` 把 `.withBean(RecentTxnCountHandler.class).withUserConfiguration(MetricDemoConfig.class)` 改为 `.withBean(VelocityMetrics.class)`(断言不变)。`MetricDemoApplication` Javadoc 里 `{@link RecentTxnCountHandler}` 改为 `{@link VelocityMetrics}`,`{@link MetricDemoConfig}` 引用删除。

- [ ] **Step 3: 跑 samples IT 确认通过**

Run: `$MVN -pl rule-samples -am test -Dtest=VelocityRuleIT -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS(frequent→命中、normal→不命中,与塌缩前等价)

- [ ] **Step 4: 提交**

```bash
git add rule-samples/src/main/java/com/sstlfsj/rule/samples/metric/VelocityMetrics.java rule-samples/src/main/java/com/sstlfsj/rule/samples/metric/MetricDemoApplication.java rule-samples/src/test/java/com/sstlfsj/rule/samples/metric/VelocityRuleIT.java
git commit -m "refactor(samples): metric 取数塌缩为单个 @MetricSource 方法(VelocityMetrics)"
```

---

## Task 7: 接口式"一 handler 多 metric"样例

**Files:**
- Create: `rule-samples/src/main/java/com/sstlfsj/rule/samples/metric/featurestore/FeatureStoreHandler.java`
- Create: `rule-samples/src/main/java/com/sstlfsj/rule/samples/metric/featurestore/FeatureStoreConfig.java`
- Create: `rule-samples/src/main/java/com/sstlfsj/rule/samples/metric/featurestore/AccountRiskRule.java`
- Test: `rule-samples/src/test/java/com/sstlfsj/rule/samples/metric/featurestore/FeatureStoreIT.java`

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.samples.metric.featurestore;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.sdk.RuleEngineClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 一个接口式 handler 服务多个 metric:FeatureStoreHandler 按 metricCode 取不同特征(account_age_days /
 * device_risk_score),取数代码一样、差别在 key。AccountRiskRule 同时用两个 metric。
 */
class FeatureStoreIT {

    @Test
    void oneHandler_servesMultipleMetrics() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        com.sstlfsj.rule.sdk.starter.RuleEngineClientAutoConfiguration.class))
                .withBean(AccountRiskRule.class)
                .withBean(FeatureStoreHandler.class)
                .withUserConfiguration(FeatureStoreConfig.class)
                .run(ctx -> {
                    RuleEngineClient client = ctx.getBean(RuleEngineClient.class);
                    // new-user:账龄 3 天 + 设备风险 80 → 命中 REVIEW(两个 metric 都来自同一 handler)
                    assertThat(client.evaluate(signup("new-user")).ruleHit()).isTrue();
                    // vip-user:账龄 1200 + 设备风险 10 → 不命中
                    assertThat(client.evaluate(signup("vip-user")).ruleHit()).isFalse();
                });
    }

    private static RuleEvent signup(String subject) {
        return RuleEvent.builder().tenantId("").sceneCode("onboarding").eventType("signup")
                .subjectId(subject).eventId(UUID.randomUUID().toString()).occurredAt(Instant.now())
                .payload(Map.of()).source(EventSource.SDK).build();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-samples -am test -Dtest=FeatureStoreIT -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败(三个类不存在)

- [ ] **Step 3: 写一 handler 多 metric**

`FeatureStoreHandler.java`:
```java
package com.sstlfsj.rule.samples.metric.featurestore;

import com.sstlfsj.rule.kernel.api.annotation.MetricSourceType;
import com.sstlfsj.rule.kernel.api.model.DataType;
import com.sstlfsj.rule.kernel.api.model.MetricQuery;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ValueSource;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricSourceHandler;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 一个 handler 服务多个 metric:所有 sourceType=FEATURE_STORE 的 metric 都进这里,取数代码一样
 * (查特征库),差别只是 query.metricCode() 决定取哪个特征。新增特征 = 加一行定义(见 FeatureStoreConfig),
 * 不改本类——这正是"配置驱动、共享后端"用接口式而非 @MetricSource 的场景。
 */
@MetricSourceType("FEATURE_STORE")
@Component
public class FeatureStoreHandler implements MetricSourceHandler {

    // 模拟特征库:subject → {特征名: 值}
    private static final Map<String, Map<String, Long>> STORE = Map.of(
            "vip-user", Map.of("account_age_days", 1200L, "device_risk_score", 10L),
            "new-user", Map.of("account_age_days", 3L, "device_risk_score", 80L));

    @Override
    public MetricValue fetch(MetricQuery query) {
        Map<String, Long> features = STORE.getOrDefault(query.subjectId(), Map.of());
        Long v = features.get(query.metricCode());   // ← 按 metricCode 取不同特征
        if (v == null) {
            return MetricValue.error("FEATURE_NOT_FOUND");
        }
        return new MetricValue(v, DataType.LONG.tag(), ValueSource.FETCHED.tag());
    }
}
```

`FeatureStoreConfig.java`:
```java
package com.sstlfsj.rule.samples.metric.featurestore;

import com.sstlfsj.rule.kernel.api.model.DataType;
import com.sstlfsj.rule.kernel.api.model.MetricDescriptor;
import com.sstlfsj.rule.sdk.source.DslMetricDefinitionSource;
import com.sstlfsj.rule.sdk.source.MetricDefinitionSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/** 两个 metric 共享 sourceType=FEATURE_STORE,各一行定义;加特征只加定义、不改 handler。 */
@Configuration
public class FeatureStoreConfig {

    @Bean
    MetricDefinitionSource featureStoreMetrics(@Value("${rule.sdk.tenant-id:}") String tenant) {
        return new DslMetricDefinitionSource(tenant, List.of(
                new MetricDescriptor("account_age_days", "FEATURE_STORE", DataType.LONG.tag(), false, 300, Map.of()),
                new MetricDescriptor("device_risk_score", "FEATURE_STORE", DataType.LONG.tag(), false, 300, Map.of())));
    }
}
```

`AccountRiskRule.java`:
```java
package com.sstlfsj.rule.samples.metric.featurestore;

import com.sstlfsj.rule.kernel.api.annotation.DecisionBinding;
import com.sstlfsj.rule.kernel.api.annotation.RuleDef;
import com.sstlfsj.rule.sdk.annotation.Condition;
import com.sstlfsj.rule.sdk.annotation.Metric;
import org.springframework.stereotype.Component;

/** 同时消费两个 metric(都来自 FeatureStoreHandler):新账户 + 高风险设备 → 复核。 */
@RuleDef(code = "account-risk", sceneCode = "onboarding", trigger = "signup",
        decisions = @DecisionBinding(code = "REVIEW", priority = 50))
@Component
public class AccountRiskRule {

    @Condition
    public boolean risky(@Metric("account_age_days") Long ageDays,
                         @Metric("device_risk_score") Long deviceRisk) {
        return ageDays != null && ageDays < 30
                && deviceRisk != null && deviceRisk >= 50;
    }
}
```

- [ ] **Step 4: 跑 IT 确认通过**

Run: `$MVN -pl rule-samples -am test -Dtest=FeatureStoreIT -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS(new-user 命中、vip-user 不命中;两个 metric 由同一 handler 按 metricCode 提供)

- [ ] **Step 5: 提交**

```bash
git add rule-samples/src/main/java/com/sstlfsj/rule/samples/metric/featurestore/ rule-samples/src/test/java/com/sstlfsj/rule/samples/metric/featurestore/FeatureStoreIT.java
git commit -m "test(samples): 一 handler 多 metric 接口式样例(FeatureStoreHandler 服务两个特征)"
```

---

## Task 8: 全量回归 + spec 状态 + README

**Files:**
- Modify: `docs/superpowers/specs/2026-06-12-annotation-metric-source-design.md`(状态行)
- Modify: `rule-samples/README.md`(metric 行更新 + 一 handler 多 metric 说明)

- [ ] **Step 1: 全量编译测试**

Run: `$MVN clean test`
Expected: 全绿(14 模块)。

- [ ] **Step 2: 更新 spec 状态 + README**

spec 首行 `> 状态:设计待评审` → `> 状态:已实现`。
`rule-samples/README.md` 的 `@Metric` 行:样例由 `metric/VelocityRule` + `RecentTxnCountHandler`/`MetricDemoConfig` 改述为 `metric/VelocityRule` + `metric/VelocityMetrics`(`@MetricSource` 方法);新增一行:`metric/featurestore/*` —— 接口式 handler 服务多个 metric(配置驱动、共享后端的取数形态)。

- [ ] **Step 3: 提交**

```bash
git add docs/superpowers/specs/2026-06-12-annotation-metric-source-design.md rule-samples/README.md
git commit -m "docs: @MetricSource(D65)标记为已实现 + README 更新 metric 样例"
```

---

## 自查清单(已核)

- **spec 覆盖**:注解(T1)/参数注入+校验(T2)/合成包装+自动 descriptor+dataType(T3)/Builder 显式 sourceType 注册(T4)/AutoConfiguration 收集(T5)/样例塌缩(T6)/一 handler 多 metric 样例(T7,用户额外要求)。spec §3–§8 各点均有对应 task。
- **类型一致**:`MetricQueryResolver.resolve/validate`、`AnnotatedMetricScanner.ScanResult(handlers, descriptors, tenantId)`、`Builder.addMetricSourceHandler(String, MetricSourceHandler)`、合成 sourceType `"__anno_metric:"+code` 跨 task 一致。
- **API 真实性**:`MetricQuery` 6 字段、`MetricValue` 3 参构造+`error`、`MetricDescriptor` 6 参、`DataType/ValueSource.tag()`、`FactResolver.factName`(public)+`coerce`(T2 改包级)、`toSourceTypeMap`/`localMetric`/`EvalContextAssembler` 装配、`DslMetricDefinitionSource` 均经源码核对。
- **不动引擎/SPI**:无 kernel 取数管线改动;接口式 `MetricSourceHandler` 保留(T7 即其参考)。
- **与 D63 兼容**:`FactResolver.coerce` 改包级不影响既有 private 调用(同类内仍可调);`factName` 已 public。

---

## 已知未覆盖(留作后续)

- **descriptor `params` 注入**(`@Fact` 读 `query.params`):自动 descriptor params 为空,本计划不做。
- **co-locate 到规则类的样例**:T5 测试已覆盖"@MetricSource 在任意 bean"能力;样例用独立 `VelocityMetrics` bean 示范(共享场景),co-locate 仅在 Javadoc 提及,不单独建样例。
