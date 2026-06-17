# 时间求值正确性 实现计划（scene 默认时区接入 + 可传求值时钟 asOf）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 `scene.default_params.timezone` 真正到达评估(填 TimeZoneResolver 恒 null 的 scene 默认槽,优先序 条件>scene>UTC),并让求值时刻 `now` 可由调用方传(asOf,缺省 Instant.now()),解决不可复现/只处理时刻/难测。

**Architecture:** scene default_params 经现有 rule_version JOIN scene 通道(RuleVersionRow +字段 → SceneSnapshotLoader 写 SceneRuleIndex,仿 decision_strategy,live)→ EvalContext 携带不可变 sceneDefaultParams → 7 个 evaluator 填 scene 默认时区。asOf 经 evaluate 请求 DTO → EvalServiceImpl 选 now → kernel 既有 `evaluateWithContext(...,now)` 入参(签名不改)。键走 `SceneDefaultParams` 常量,无魔法串。

**Tech Stack:** Java 25、Spring Boot 4、MyBatis-Plus(@Select record 映射)、JUnit5+AssertJ、Mockito。

**前置:** 跑 Maven 前用 `mvn-env` skill 设 `$MVN`(JDK25)。设计依据 `docs/superpowers/specs/2026-06-13-time-evaluation-correctness-design.md`。多参类型按全局约定走 Lombok `@Builder`(本计划无新多参值类型;RuleVersionRow/EvalContext 用兼容构造器)。

---

## 文件结构

kernel:
- 新建 `api/model/SceneDefaultParams.java`(键常量)
- 改 `internal/index/SceneRuleIndex.java`(+defaultParams get/set)
- 改 `api/model/EvalContext.java`(+sceneDefaultParams + 兼容构造器)
- 改 `internal/context/EvalContextAssembler.java`(assemble +入参)
- 改 `internal/engine/EvalEngine.java`(从 index 取 + 传 assemble)
- 改 `internal/codec/RuleVersionRow.java`(+defaultParamsJson)
- 改 7 个 evaluator + `internal/condition/time/TimeZoneResolver.java`(scene 默认兜底)

eval-svc:
- 改 `internal/repository/RuleVersionReadMapper.java`(3 SQL 加 s.default_params)
- 改 `internal/snapshot/SceneSnapshotLoader.java`(载 default_params 进 index)
- 改 `internal/service/EvalServiceImpl.java`(asOf)

rule-api:
- 改 `web/api/EvalController.java` + evaluate 请求 DTO `EvalEventRequest`(+asOf)

config-svc:
- 改 `internal/service/SceneServiceImpl.java`(timezone 合法性校验)

---

## Task 1: SceneDefaultParams 常量

**Files:**
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/SceneDefaultParams.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/SceneDefaultParamsTest.java`

- [ ] **Step 1: 写实现**
```java
package com.sstlfsj.rule.kernel.api.model;

/**
 * scene 级默认参数(scene.default_params)的键常量,单一真相源,杜绝魔法串。
 * 当前仅 timezone 有消费者(时间类算子兜底时区);currency 等将来扩此。
 * 与 {@link ConditionParams#TIMEZONE} 同字面但分属"scene 配置键 / 条件 param 键"两命名空间。
 */
public final class SceneDefaultParams {
    private SceneDefaultParams() {}

    /** 场景默认时区(IANA 名),时间类算子在条件未声明 timezone 时的兜底。 */
    public static final String TIMEZONE = "timezone";
}
```

- [ ] **Step 2: 写测试**
```java
package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SceneDefaultParamsTest {
    @Test
    void timezoneKeyValue() {
        assertThat(SceneDefaultParams.TIMEZONE).isEqualTo("timezone");
    }
}
```

- [ ] **Step 3: 跑测试**
Run: `$MVN -pl rule-kernel -am test -Dtest='SceneDefaultParamsTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 4: Commit**
```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/SceneDefaultParams.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/SceneDefaultParamsTest.java
git commit -m "feat(kernel): SceneDefaultParams 键常量(timezone,无魔法串)"
```

---

## Task 2: SceneRuleIndex +defaultParams

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/index/SceneRuleIndex.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/index/SceneRuleIndexTest.java`(若不存在则新建)

- [ ] **Step 1: 写失败测试**
```java
@Test
void defaultParams_setAndGet() {
    SceneRuleIndex index = new SceneRuleIndex();
    assertThat(index.getDefaultParams("1", "scene")).isEmpty();
    index.setDefaultParams("1", "scene", java.util.Map.of("timezone", "Asia/Shanghai"));
    assertThat(index.getDefaultParams("1", "scene")).containsEntry("timezone", "Asia/Shanghai");
}
```
(若 SceneRuleIndexTest 不存在,新建文件,package `com.sstlfsj.rule.kernel.index`,import `SceneRuleIndex`、AssertJ。)

- [ ] **Step 2: 跑确认失败**
Run: `$MVN -pl rule-kernel -am test -Dtest='SceneRuleIndexTest#defaultParams_setAndGet' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败(方法不存在)。

- [ ] **Step 3: 实现**
在 `SceneRuleIndex` 加(仿现有 `strategies` 字段 + setStrategy/getStrategy):
```java
    private final Map<String, Map<String, Object>> defaultParams = new ConcurrentHashMap<>();

    /**
     * 设置场景默认参数(scene.default_params)。
     *
     * @param tenantId  租户标识
     * @param sceneCode 场景编码
     * @param params    默认参数 map(不可变拷贝)
     */
    public void setDefaultParams(String tenantId, String sceneCode, Map<String, Object> params) {
        defaultParams.put(tenantId + ":" + sceneCode, params == null ? Map.of() : Map.copyOf(params));
    }

    /**
     * 获取场景默认参数,未设置返回空 map。
     *
     * @param tenantId  租户标识
     * @param sceneCode 场景编码
     * @return 默认参数 map(不可变)
     */
    public Map<String, Object> getDefaultParams(String tenantId, String sceneCode) {
        return defaultParams.getOrDefault(tenantId + ":" + sceneCode, Map.of());
    }
```
(`Map`/`ConcurrentHashMap` 已 import。`remove(tenantId,sceneCode)` 现有方法可顺手清 defaultParams:在 `remove` 里加 `defaultParams.keySet().removeIf(k -> k.startsWith(tenantId + ":" + sceneCode + ":"))`——注意 defaultParams 的 key 是 `tenant:scene` 无尾冒号,改为精确 `defaultParams.remove(tenantId + ":" + sceneCode)`。)

- [ ] **Step 4: 跑测试通过**
Run: `$MVN -pl rule-kernel -am test -Dtest='SceneRuleIndexTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 5: Commit**
```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/index/SceneRuleIndex.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/index/SceneRuleIndexTest.java
git commit -m "feat(kernel): SceneRuleIndex 承载 scene default_params(get/set,仿 strategy)"
```

---

## Task 3: EvalContext +sceneDefaultParams + 兼容构造器

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/EvalContext.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/EvalContextTest.java`(若不存在则新建)

- [ ] **Step 1: 写失败测试**
```java
@Test
void sceneDefaultParams_carriedAndImmutable() {
    RuleEvent e = new RuleEvent("t", "s", "E", "u", "evt", java.time.Instant.now(),
            java.util.Map.of(), java.util.Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
    EvalContext ctx = new EvalContext("t", e, null, java.util.Map.of(), java.time.Instant.now(),
            java.util.Map.of("timezone", "Asia/Shanghai"));
    assertThat(ctx.sceneDefaultParams()).containsEntry("timezone", "Asia/Shanghai");
}

@Test
void compatConstructor_defaultsEmptySceneParams() {
    RuleEvent e = new RuleEvent("t", "s", "E", "u", "evt", java.time.Instant.now(),
            java.util.Map.of(), java.util.Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
    EvalContext ctx = new EvalContext("t", e, null, java.util.Map.of(), java.time.Instant.now());
    assertThat(ctx.sceneDefaultParams()).isEmpty();
}
```
(新建 EvalContextTest:package `com.sstlfsj.rule.kernel.api.model`,import AssertJ。)

- [ ] **Step 2: 跑确认失败**
Run: `$MVN -pl rule-kernel -am test -Dtest='EvalContextTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败。

- [ ] **Step 3: 实现**
`EvalContext` 加字段 + 6 参主构造器 + 5 参兼容构造器:
```java
    private final Map<String, Object> sceneDefaultParams;

    public EvalContext(String tenantId, RuleEvent event, Subject subject,
                       Map<String, MetricValue> metrics, Instant now,
                       Map<String, Object> sceneDefaultParams) {
        this.tenantId = tenantId;
        this.event = event;
        this.subject = subject;
        this.metrics = Map.copyOf(metrics);
        this.now = now;
        this.sceneDefaultParams = sceneDefaultParams == null ? Map.of() : Map.copyOf(sceneDefaultParams);
    }

    /** 兼容构造器:无 scene 默认参数(SDK 本地模式 / 测试),默认空 map。 */
    public EvalContext(String tenantId, RuleEvent event, Subject subject,
                       Map<String, MetricValue> metrics, Instant now) {
        this(tenantId, event, subject, metrics, now, Map.of());
    }

    /** 场景默认参数(scene.default_params 快照,ambient 配置);键见 SceneDefaultParams。 */
    public Map<String, Object> sceneDefaultParams() { return sceneDefaultParams; }
```
(把原 5 参构造器体并入 6 参,5 参改为委托;原有其它字段/方法不动。)

- [ ] **Step 4: 跑测试通过 + kernel 全量(确认大量 5 参老调用点经兼容构造器仍编译)**
Run: `$MVN -pl rule-kernel -am test`
Expected: 全绿(5 参兼容构造器保住所有老调用点)。

- [ ] **Step 5: Commit**
```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/EvalContext.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/EvalContextTest.java
git commit -m "feat(kernel): EvalContext 携带 sceneDefaultParams + 5 参兼容构造器"
```

---

## Task 4: EvalContextAssembler.assemble +入参 + EvalEngine 传入

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/context/EvalContextAssembler.java`
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/engine/EvalEngine.java`

- [ ] **Step 1: assemble 加 sceneDefaultParams 入参**
`EvalContextAssembler.assemble(RuleEvent event, List<RuleVersionSnapshot> candidates, Instant now)` 签名改为追加 `Map<String,Object> sceneDefaultParams`:
```java
    public EvalContext assemble(RuleEvent event,
                                List<RuleVersionSnapshot> candidates,
                                Instant now,
                                Map<String, Object> sceneDefaultParams) {
```
方法体内两处 `new EvalContext(event.tenantId(), event, subject, ... , now)`(无解析器退化分支 + 末尾正常分支)都改为 6 参,末尾追加 `sceneDefaultParams`。例如末尾:
```java
        return new EvalContext(event.tenantId(), event, subject, metrics, now, sceneDefaultParams);
```
(退化分支同样追加 `sceneDefaultParams`。)

- [ ] **Step 2: EvalEngine 从 index 取并传**
`EvalEngine` 第 ~136 行 `EvalContext ctx = contextAssembler.assemble(event, passed, now);` 改为:
```java
        Map<String, Object> sceneDefaults = index.getDefaultParams(event.tenantId(), event.sceneCode());
        EvalContext ctx = contextAssembler.assemble(event, passed, now, sceneDefaults);
```
(`index` 是 EvalEngine 既有字段;`Map` 已 import 或加 `import java.util.Map;`。)

- [ ] **Step 3: 编译 + kernel 全量**
Run: `$MVN -pl rule-kernel -am test`
Expected: 全绿。assemble 多一入参,所有调用点(仅 EvalEngine 一处生产 + 测试)同步;若有测试直接调 `assemble(...3参...)` 编译失败,改为传 `Map.of()` 第 4 参。

- [ ] **Step 4: Commit**
```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/context/EvalContextAssembler.java \
        rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/engine/EvalEngine.java
git commit -m "feat(kernel): EvalEngine 从 index 取 sceneDefaultParams 注入 EvalContext"
```

---

## Task 5: RuleVersionRow +defaultParamsJson + SQL + SceneSnapshotLoader 载入 index

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/codec/RuleVersionRow.java`
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/repository/RuleVersionReadMapper.java`
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/snapshot/SceneSnapshotLoader.java`
- Test: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/integration/` 下加/复用集成测试(见 Step 5)

- [ ] **Step 1: RuleVersionRow 加 defaultParamsJson(canonical 末尾)**
在 record 组件末尾(`scriptSourceJson` 之后)加:
```java
        String scriptSourceJson,
        /** scene.default_params JSON 字符串(scene 级,供 SceneSnapshotLoader 写 SceneRuleIndex);可能为 null。 */
        String defaultParamsJson
```
两个兼容构造器(9 参、13 参)的 `this(...)` 委托末尾各追加一个 `null`(defaultParamsJson 默认 null)。13 参那个委托原传到 `scriptSourceJson=null`,现追加 `, null`;9 参委托链同理保持其原 default(version 0L 等)末尾 + `null`。

- [ ] **Step 2: 3 个 scene-JOIN SQL 加列**
`RuleVersionReadMapper` 的 `loadAllActive`、`loadActiveByScene`、`loadById` 三个 `@Select` 各在 `rv.script_source AS scriptSourceJson` 后加一行:
```
              ,s.default_params  AS defaultParamsJson
```
(`latestVersionIdByRule` 只返 Long,不动。)

- [ ] **Step 3: SceneSnapshotLoader 解析并写 index**
注入 `ObjectMapper`(Spring 全局)用于解析 defaultParamsJson;在 `applyStrategiesToIndex(rows, index)` 里每行除 setStrategy 外加 setDefaultParams:
```java
    private final ObjectMapper objectMapper;

    public SceneSnapshotLoader(RuleVersionReadMapper mapper, SnapshotAssembler assembler, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.assembler = assembler;
        this.objectMapper = objectMapper;
    }

    // applyStrategiesToIndex 循环内,setStrategy 之后:
            index.setDefaultParams(String.valueOf(row.tenantId()), row.sceneCode(),
                    parseDefaultParams(row.defaultParamsJson()));

    /** 解析 scene.default_params JSON 为 Map;null/空/非法 → 空 map(不阻断索引加载)。 */
    private Map<String, Object> parseDefaultParams(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
```
import:`tools.jackson.databind.ObjectMapper`(项目用 jackson3,与 `EvalAutoConfiguration.snapshotAssembler` 注入的 `tools.jackson.databind.ObjectMapper` 一致)、`tools.jackson.core.type.TypeReference`。
> 注:SceneSnapshotLoader 是 `@Component`,新增构造器参数 ObjectMapper 由 Spring 注入(全局 Bean 已存在,EvalAutoConfiguration 用过)。

- [ ] **Step 4: 编译 eval-svc**
Run: `$MVN -pl rule-eval-svc -am -DskipTests compile`
Expected: BUILD SUCCESS。`RuleVersionRow` 三 SQL 全投影映射 canonical 15 参构造器。

- [ ] **Step 5: 集成测试(真 DB,验证 default_params 流到 index)**
在 config-svc 集成测试(Testcontainers,既有 `MetadataServiceIntegrationTest` 等同款 setup)新增用例:建带 `default_params={"timezone":"Asia/Shanghai"}` 的 scene + 一条 ACTIVE 规则 → 经 `SceneSnapshotLoader.loadAllWithStrategy(index)` → 断言 `index.getDefaultParams(tenant, scene).get("timezone")` == "Asia/Shanghai"。
(若 eval 侧无现成 Testcontainers 集成测试夹具,改为单测:mock `RuleVersionReadMapper.loadAllActive()` 返回一个带 `defaultParamsJson="{\"timezone\":\"Asia/Shanghai\"}"` 的 RuleVersionRow,调 loadAllWithStrategy,断言 index.getDefaultParams 命中。优先单测,避免 e2e 依赖。)

- [ ] **Step 6: 跑测试 + 提交**
Run: `$MVN -pl rule-eval-svc -am test`
Expected: 全绿。
```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/codec/RuleVersionRow.java \
        rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/repository/RuleVersionReadMapper.java \
        rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/snapshot/SceneSnapshotLoader.java \
        rule-eval-svc/src/test/
git commit -m "feat(eval): scene.default_params 经 RuleVersionRow JOIN 通道载入 SceneRuleIndex"
```

---

## Task 6: 时间/比较算子用 scene 默认时区 + TimeZoneResolver 兜底

**Files:**
- Modify: `rule-kernel/.../internal/condition/time/TimeZoneResolver.java`
- Modify: 7 个 evaluator:`EqEvaluator` `NeqEvaluator` `BetweenEvaluator` `NotBetweenEvaluator` `DateComparisonSupport` `TimeWindowEvaluator` `OccurredAtEvaluator`
- Test: `rule-kernel/.../condition/TimeWindowEvaluatorTest`(或新建)+ `TimeZoneResolverTest`

- [ ] **Step 1: TimeZoneResolver 对 scene 默认值兜底**
现状 `resolve(paramsTimezone, sceneDefaultTimezone)` 两处 `ZoneId.of` 均会对非法值抛。改为:**条件 params.timezone 维持抛出语义不变;scene 默认值非法时退回继续(→ UTC)**:
```java
    public static ZoneId resolve(String paramsTimezone, String sceneDefaultTimezone) {
        if (paramsTimezone != null && !paramsTimezone.isBlank()) {
            return ZoneId.of(paramsTimezone.trim()); // 条件级非法仍抛(语义不变)
        }
        if (sceneDefaultTimezone != null && !sceneDefaultTimezone.isBlank()) {
            try {
                return ZoneId.of(sceneDefaultTimezone.trim());
            } catch (RuntimeException e) {
                return ZoneOffset.UTC; // scene 默认脏数据兜底,不阻断评估
            }
        }
        return ZoneOffset.UTC;
    }
```

- [ ] **Step 2: 写失败测试(TimeZoneResolver + 一个 evaluator)**
TimeZoneResolverTest 加:
```java
@Test
void sceneDefault_usedWhenParamsAbsent() {
    assertThat(TimeZoneResolver.resolve(null, "Asia/Shanghai"))
            .isEqualTo(java.time.ZoneId.of("Asia/Shanghai"));
}
@Test
void paramsOverridesScene() {
    assertThat(TimeZoneResolver.resolve("UTC", "Asia/Shanghai"))
            .isEqualTo(java.time.ZoneOffset.UTC);
}
@Test
void illegalSceneDefault_fallsBackUtc() {
    assertThat(TimeZoneResolver.resolve(null, "Asia/Xxx"))
            .isEqualTo(java.time.ZoneOffset.UTC);
}
```
TimeWindowEvaluatorTest 加(条件不带 timezone、ctx 带 scene timezone → 按 scene 时区判时段):构造 ctx 用 6 参 EvalContext 传 `Map.of("timezone","Asia/Shanghai")`,某 UTC 时刻在上海时区落在 [09:00,18:00] → 命中。(具体断言按该测试类既有 now/params 夹具写。)

- [ ] **Step 3: 7 个 evaluator 改用 scene 默认兜底**
每处 `TimeZoneResolver.resolve((String) node.params().get(ConditionParams.TIMEZONE), null)` 改为:
```java
TimeZoneResolver.resolve((String) node.params().get(ConditionParams.TIMEZONE),
                         (String) ctx.sceneDefaultParams().get(SceneDefaultParams.TIMEZONE))
```
`TimeWindowEvaluator`/`OccurredAtEvaluator` 用 `params.get("timezone")` 的两处,同步改为 `params.get(ConditionParams.TIMEZONE)`(消魔法串)+ 第二参 `ctx.sceneDefaultParams().get(SceneDefaultParams.TIMEZONE)`。import `ConditionParams`、`SceneDefaultParams`。`ctx` 在各 evaluate(node, ctx) 入参里。

- [ ] **Step 4: 跑测试 + kernel 全量**
Run: `$MVN -pl rule-kernel -am test`
Expected: 全绿(既有时间算子测试 ctx 用 5 参兼容构造器 → scene 默认空 → 行为不变;新用例验证 scene 默认生效)。

- [ ] **Step 5: Commit**
```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/
git commit -m "feat(kernel): 时间/比较算子用 scene 默认时区兜底(条件>scene>UTC)+ TimeZoneResolver scene 脏值兜底"
```

---

## Task 7: asOf —— 调用方可传求值时钟

**Files:**
- Modify: evaluate 请求 DTO `rule-api/.../web/api/EvalEventRequest.java`(加 asOf)
- Modify: `rule-api/.../web/api/EvalController.java`(透传 asOf)
- Modify: `rule-eval-svc/.../internal/service/EvalServiceImpl.java`(+evaluate(event, asOf) + doEvaluate 用 asOf)
- Modify: `rule-eval-svc/.../api/service/EvalService.java`(加 overload)
- Test: `EvalServiceImplTest`(asOf 用例)

- [ ] **Step 1: Read 现状**
Read `EvalEventRequest`(字段)、`EvalController`(evaluate/dry-run 怎么调 evalService + toEvent)、`EvalService` 接口、`EvalServiceImpl` 的 `evaluate(RuleEvent)` → `doEvaluate(...)`(`Instant evalNow = Instant.now()` 在 doEvaluate)。

- [ ] **Step 2: EvalEventRequest 加可选 asOf**
record 加分量 `java.time.Instant asOf`(可选,null 表示用 now)。若 EvalEventRequest 是 record,加到末尾;Jackson 反序列化 ISO-8601 Instant。

- [ ] **Step 3: EvalService 加 overload + Impl 透传**
`EvalService` 接口加 `EvalResult evaluate(RuleEvent event, Instant asOf);`(原 `evaluate(RuleEvent)` 保留,默认 `evaluate(event, null)`)。
`EvalServiceImpl`:`doEvaluate` 加 `Instant asOf` 入参,首行 `Instant evalNow = asOf != null ? asOf : Instant.now();`(替代原 `Instant.now()`);`evaluate(event)` → `doEvaluate(event, mode, false, null, null)`;新 `evaluate(event, asOf)` → `doEvaluate(event, mode, false, null, asOf)`。dry-run 路径同样把 asOf 透传(dry-run 入口加 asOf 或默认 null)。
> 只改 now 的来源,其余 doEvaluate 逻辑不动。

- [ ] **Step 4: EvalController 透传**
`evaluate` 端点:`evalService.evaluate(toEvent(req), req.asOf())`。

- [ ] **Step 5: 写测试**
`EvalServiceImplTest` 加:传固定 asOf(如 `Instant.parse("2020-01-01T00:00:00Z")`)调 evaluate,断言 outcome.context().now() == 该 asOf(可复现);不传(null)→ now ≈ Instant.now()(断言在调用前后 Instant 之间)。按该测试类既有 mock 夹具(mock evalEngine 捕获 now 入参,或断言 context().now())。

- [ ] **Step 6: 跑测试 + eval-svc 全量**
Run: `$MVN -pl rule-eval-svc -am test`
Expected: 全绿。

- [ ] **Step 7: Commit**
```bash
git add rule-api/src/main/java/com/sstlfsj/rule/web/api/ \
        rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/
git commit -m "feat(eval): evaluate 支持调用方传 asOf 求值时钟(缺省 Instant.now)"
```

---

## Task 8: scene 创建/更新期 timezone 合法性校验

**Files:**
- Modify: `rule-config-svc/.../internal/service/SceneServiceImpl.java`
- Test: `SceneServiceImplPayloadTypeTest`(或同类)加用例

- [ ] **Step 1: 写失败测试**
```java
@Test
void createScene_illegalTimezone_throws() {
    // 复用该测试类构造 impl 的方式(null collaborators 即可,校验在 mapper 调用前)
    assertThatThrownBy(() -> sceneService.createScene("1","s","n",null,"PUSH","USER",
            java.util.List.of(), java.util.List.of(),
            java.util.Map.of("timezone","Asia/Xxx"), "actor"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("timezone");
}
```

- [ ] **Step 2: 跑确认失败**
Run: `$MVN -pl rule-config-svc -am test -Dtest='SceneServiceImplPayloadTypeTest#createScene_illegalTimezone_throws' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL(不抛)。

- [ ] **Step 3: 实现**
`SceneServiceImpl` 加私有校验并在 createScene/updateScene 处理 defaultParams 前调用:
```java
    private void validateDefaultParams(Map<String, Object> defaultParams) {
        if (defaultParams == null) return;
        Object tz = defaultParams.get(SceneDefaultParams.TIMEZONE);
        if (tz != null) {
            try {
                java.time.ZoneId.of(tz.toString());
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("非法 scene default_params.timezone=" + tz
                        + "（须为合法 IANA 时区名，如 Asia/Shanghai）");
            }
        }
    }
```
createScene 在 `scene.setDefaultParams(defaultParams)` 前调 `validateDefaultParams(defaultParams)`;updateScene 在 `if (defaultParams != null) scene.setDefaultParams(...)` 前调。import `com.sstlfsj.rule.kernel.api.model.SceneDefaultParams`、`java.util.Map`(应已导)。

- [ ] **Step 4: 跑测试通过 + config-svc 全量**
Run: `$MVN -pl rule-config-svc -am test`
Expected: 全绿。

- [ ] **Step 5: Commit**
```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/SceneServiceImpl.java \
        rule-config-svc/src/test/
git commit -m "feat(config): scene 创建/更新期校验 default_params.timezone 合法 IANA"
```

---

## Task 9: 全量 clean test

- [ ] **Step 1: 全量**
Run: `$MVN clean test`
Expected: 27 模块全绿。失败按纪律修,不 skip。

---

## Task 10: DB 端到端功能测试(起真实服务)

- [ ] **Step 1: 打包起服务**
`$MVN -pl rule-app -am package -DskipTests` → `java -jar rule-app/target/rule-app-1.0.0-SNAPSHOT.jar`,确认就绪。

- [ ] **Step 2: 剧本**
1. 建 scene:`default_params.timezone="Asia/Xxx"`(非法)→ 期望 400;`"Asia/Shanghai"`(合法)→ 200。
2. 建并发布一条 time.window 规则(条件**不带** timezone,如 start=09:00 end=18:00)。
3. evaluate 传 `asOf` = 某 UTC 时刻(该时刻在上海时区落在 09:00–18:00 内)→ 命中;传落在区间外的 asOf → 不命中(证 scene 默认时区生效 + asOf 可控)。
4. 同一 asOf 重复评估 → 结果一致(可复现)。
5. PATCH 改 scene timezone 为另一区 → 同一 asOf 评估结果随之变(证 live)。
6. 清理测试 scene/规则,恢复基线。

- [ ] **Step 3: 记录结果 + 停服务**
回报各步结果;`pkill -f rule-app-1.0.0-SNAPSHOT.jar`。

---

## Self-Review

**Spec 覆盖:** §3 A1→T1、A2→T2/T3/T4/T5、A3→T6、A4→T8;§4 B(asOf)→T7;§6 错误处理→T6(scene 兜底)+T8(创建校验)+T7(asOf 400 框架);§7 测试+DB e2e→T9/T10。全覆盖。

**类型一致性:** `SceneDefaultParams.TIMEZONE` T1 定义、T6/T8 引用;`SceneRuleIndex.getDefaultParams/setDefaultParams` T2 定义、T4/T5 引用;`EvalContext(...,sceneDefaultParams)` 6 参 + 5 参兼容 T3 定义、T4(assemble)/T6(ctx.sceneDefaultParams())引用;`assemble(event,candidates,now,sceneDefaultParams)` T4 定义、EvalEngine 调用一致;`RuleVersionRow.defaultParamsJson()` T5 定义、SceneSnapshotLoader 读;`EvalService.evaluate(event,asOf)` T7 定义、Controller 调用;`TimeZoneResolver.resolve(params,sceneDefault)` 签名不变(语义增兜底)。

**占位扫描:** 无 TBD;T5 Step5/T7 Step1 的"Read 现状/按既有夹具"是 TDD 定位指引(测试夹具因类而异),非占位——给了断言意图 + 具体值;其余均含完整代码。T5 给了"集成测试优先降级单测"的明确两选一,非含糊。
