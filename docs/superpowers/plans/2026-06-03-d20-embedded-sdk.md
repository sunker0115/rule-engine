# D20 Embedded SDK 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把评估热路径从 `rule-eval-svc` 下沉到 `rule-kernel`，新建 `rule-sdk` 和 `rule-sdk-spring-boot-starter` 模块，让业务服务无需 HTTP 调用即可在 JVM 内本地评估规则。

**Architecture:** `EvalEngine`（纯 Java）从 `EvalServiceImpl.doEvaluate()` 提取编排逻辑下沉到 `rule-kernel`，`SceneRuleIndex` 和 `EvalContextAssembler` 同步迁入；`rule-sdk` 持有 `SnapshotPoller`（HTTP 轮询 rule-api）和 `RuleEngineClient` 门面；`rule-eval-svc` 的 `EvalServiceImpl` 变薄为调用 `EvalEngine` + 副作用壳；`rule-api` 新增 `GET /api/v1/sdk/snapshots` 端点。

**Tech Stack:** Java 25、Spring Boot 4、Jackson（`rule-sdk` 用于 HTTP 响应反序列化）、`java.net.http.HttpClient`（JDK 内置，`rule-sdk` 零额外依赖）

---

## 文件清单

### rule-kernel（改动 + 新增）

| 操作 | 文件 |
|------|------|
| 迁入（去 @Component） | `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/index/SceneRuleIndex.java` |
| 迁入（去 @Component） | `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/context/EvalContextAssembler.java` |
| 新增 | `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/engine/EvalEngine.java` |
| 新增测试 | `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/engine/EvalEngineTest.java` |
| 新增测试 | `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/index/SceneRuleIndexTest.java`（已有则扩充）|

### rule-eval-svc（改动）

| 操作 | 文件 |
|------|------|
| 删除 | `rule-eval-svc/…/internal/index/SceneRuleIndex.java`（迁移到 kernel） |
| 删除 | `rule-eval-svc/…/internal/context/EvalContextAssembler.java`（迁移到 kernel） |
| 改动 | `rule-eval-svc/…/internal/service/EvalServiceImpl.java`（委托 EvalEngine，保留副作用） |
| 改动 | `rule-eval-svc/…/EvalAutoConfiguration.java`（注册 EvalEngine Bean） |
| 改动 | `rule-eval-svc/…/internal/listener/RuleIndexEventListener.java`（import 改为 kernel 包路径） |
| 改动 | `rule-eval-svc/…/internal/listener/SceneIndexEventListener.java`（同上） |
| 改动 | `rule-eval-svc/…/internal/listener/IndexStartupLoader.java`（同上） |

### rule-api（改动）

| 操作 | 文件 |
|------|------|
| 新增 | `rule-api/src/main/java/com/sstlfsj/rule/web/sdk/SdkSnapshotController.java` |
| 改动 | `rule-api/src/main/java/com/sstlfsj/rule/web/ApiAutoConfiguration.java`（注册 SdkSnapshotController） |
| 新增测试 | `rule-api/src/test/java/com/sstlfsj/rule/web/sdk/SdkSnapshotControllerTest.java` |

### rule-sdk（新建模块）

| 操作 | 文件 |
|------|------|
| 新增 | `rule-sdk/pom.xml` |
| 新增 | `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/FetchMode.java` |
| 新增 | `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/EvalResultListener.java` |
| 新增 | `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/EvalSessionListener.java` |
| 新增 | `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/SnapshotPoller.java` |
| 新增 | `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/RuleEngineClient.java` |
| 新增测试 | `rule-sdk/src/test/java/com/sstlfsj/rule/sdk/SnapshotPollerTest.java` |
| 新增测试 | `rule-sdk/src/test/java/com/sstlfsj/rule/sdk/RuleEngineClientTest.java` |

### rule-sdk-spring-boot-starter（新建模块）

| 操作 | 文件 |
|------|------|
| 新增 | `rule-sdk-spring-boot-starter/pom.xml` |
| 新增 | `rule-sdk-spring-boot-starter/src/main/java/com/sstlfsj/rule/sdk/starter/SdkProperties.java` |
| 新增 | `rule-sdk-spring-boot-starter/src/main/java/com/sstlfsj/rule/sdk/starter/RuleEngineClientAutoConfiguration.java` |
| 新增 | `rule-sdk-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` |
| 新增测试 | `rule-sdk-spring-boot-starter/src/test/java/com/sstlfsj/rule/sdk/starter/RuleEngineClientAutoConfigurationTest.java` |

### 根 pom.xml（改动）

追加两个 `<module>` 条目：`rule-sdk`、`rule-sdk-spring-boot-starter`

---

## Maven 环境

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
```

---

## Task 1：SceneRuleIndex 迁入 rule-kernel

**目标：** 把 `SceneRuleIndex` 从 `rule-eval-svc` 迁移到 `rule-kernel`，去掉 `@Component`，修正所有 import 路径，保持逻辑不变。

**Files:**
- 新建: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/index/SceneRuleIndex.java`
- 删除: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/index/SceneRuleIndex.java`
- 改动: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/listener/RuleIndexEventListener.java`
- 改动: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/listener/SceneIndexEventListener.java`
- 改动: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/listener/IndexStartupLoader.java`
- 改动: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImpl.java`（import 路径）
- 改动: `rule-eval-svc/pom.xml`（确认已依赖 rule-kernel，通常已有）
- 测试: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/index/SceneRuleIndexTest.java`

- [ ] **Step 1: 在 rule-kernel 新建 SceneRuleIndex**

```java
// rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/index/SceneRuleIndex.java
package com.sstlfsj.rule.kernel.internal.index;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存倒排索引：(tenantId, sceneCode, eventType) → List&lt;RuleVersionSnapshot&gt;。
 * 纯 Java，无 Spring 依赖，可在 rule-eval-svc 和 rule-sdk 中共用。
 */
public class SceneRuleIndex {

    private final Map<String, List<RuleVersionSnapshot>> index = new ConcurrentHashMap<>();

    /**
     * 返回给定租户、场景和事件类型对应的活跃规则版本快照列表。
     * 先查精确 key，再查通配 key（"*"），合并去重返回。
     */
    public List<RuleVersionSnapshot> match(String tenantId, String sceneCode, String eventType) {
        String exactKey    = tenantId + ":" + sceneCode + ":" + eventType;
        String wildcardKey = tenantId + ":" + sceneCode + ":*";

        List<RuleVersionSnapshot> exact    = index.getOrDefault(exactKey, List.of());
        List<RuleVersionSnapshot> wildcard = index.getOrDefault(wildcardKey, List.of());

        if (exact.isEmpty()) return wildcard;
        if (wildcard.isEmpty()) return exact;

        List<RuleVersionSnapshot> merged = new ArrayList<>(exact);
        for (RuleVersionSnapshot snap : wildcard) {
            if (exact.stream().noneMatch(s -> s.ruleVersionId().equals(snap.ruleVersionId()))) {
                merged.add(snap);
            }
        }
        return List.copyOf(merged);
    }

    /** 更新给定租户、场景和事件类型的索引条目。 */
    public void update(String tenantId, String sceneCode, String eventType,
                       List<RuleVersionSnapshot> snapshots) {
        String key = tenantId + ":" + sceneCode + ":" + eventType;
        index.put(key, List.copyOf(snapshots));
    }

    /** 删除给定租户和场景的所有索引条目（场景被禁用时调用）。 */
    public void remove(String tenantId, String sceneCode) {
        index.keySet().removeIf(k -> k.startsWith(tenantId + ":" + sceneCode + ":"));
    }
}
```

- [ ] **Step 2: 在 rule-kernel 新建 SceneRuleIndexTest（先写，故意失败）**

```java
// rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/index/SceneRuleIndexTest.java
package com.sstlfsj.rule.kernel.internal.index;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SceneRuleIndexTest {

    private static RuleVersionSnapshot snap(Long id, String tenant, String scene, List<String> types) {
        return new RuleVersionSnapshot(id, scene, tenant,
                new AndNode(List.of()), List.of(), List.of(), types);
    }

    @Test
    void match_exactEventType_returnsSnap() {
        SceneRuleIndex idx = new SceneRuleIndex();
        RuleVersionSnapshot s = snap(1L, "t1", "payment", List.of("ORDER"));
        idx.update("t1", "payment", "ORDER", List.of(s));

        assertThat(idx.match("t1", "payment", "ORDER")).containsExactly(s);
    }

    @Test
    void match_wildcardFallback_returnsSnap() {
        SceneRuleIndex idx = new SceneRuleIndex();
        RuleVersionSnapshot s = snap(2L, "t1", "payment", List.of());
        idx.update("t1", "payment", "*", List.of(s));

        assertThat(idx.match("t1", "payment", "ANYTHING")).containsExactly(s);
    }

    @Test
    void match_mergesExactAndWildcard_noDuplicates() {
        SceneRuleIndex idx = new SceneRuleIndex();
        RuleVersionSnapshot exact = snap(1L, "t1", "fraud", List.of("LOGIN"));
        RuleVersionSnapshot wild  = snap(2L, "t1", "fraud", List.of());
        idx.update("t1", "fraud", "LOGIN", List.of(exact));
        idx.update("t1", "fraud", "*",     List.of(wild));

        List<RuleVersionSnapshot> result = idx.match("t1", "fraud", "LOGIN");
        assertThat(result).hasSize(2).contains(exact, wild);
    }

    @Test
    void remove_deletesAllEntriesForScene() {
        SceneRuleIndex idx = new SceneRuleIndex();
        idx.update("t1", "payment", "ORDER", List.of(snap(1L, "t1", "payment", List.of("ORDER"))));
        idx.remove("t1", "payment");
        assertThat(idx.match("t1", "payment", "ORDER")).isEmpty();
    }
}
```

- [ ] **Step 3: 运行测试，确认通过**

```bash
$MVN -pl rule-kernel -am test -Dtest='SceneRuleIndexTest' -Dsurefire.failIfNoSpecifiedTests=false
```

期望：BUILD SUCCESS

- [ ] **Step 4: 删除 rule-eval-svc 中的旧 SceneRuleIndex**

删除文件：`rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/index/SceneRuleIndex.java`

- [ ] **Step 5: 修正 rule-eval-svc 中引用 SceneRuleIndex 的 import**

以下四个文件的 import 从 `com.sstlfsj.rule.eval.internal.index.SceneRuleIndex` 改为 `com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex`：

- `rule-eval-svc/…/listener/RuleIndexEventListener.java`
- `rule-eval-svc/…/listener/SceneIndexEventListener.java`
- `rule-eval-svc/…/listener/IndexStartupLoader.java`
- `rule-eval-svc/…/service/EvalServiceImpl.java`

- [ ] **Step 6: 运行 rule-eval-svc 全量测试，确认无编译错误**

```bash
$MVN -pl rule-eval-svc -am test
```

期望：BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/index/ \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/index/ \
        rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/
git commit -m "refactor(kernel): 将 SceneRuleIndex 迁入 rule-kernel，去掉 Spring 注解"
```

---

## Task 2：EvalContextAssembler 迁入 rule-kernel

**目标：** 把 `EvalContextAssembler` 从 `rule-eval-svc` 迁移到 `rule-kernel`，去掉 `@Component`，改为构造器注入。

**Files:**
- 新建: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/context/EvalContextAssembler.java`
- 删除: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/context/EvalContextAssembler.java`
- 改动: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImpl.java`（import）
- 改动: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/EvalAutoConfiguration.java`（注册 EvalContextAssembler Bean）
- 新增测试: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/context/EvalContextAssemblerTest.java`

- [ ] **Step 1: 在 rule-kernel 新建 EvalContextAssembler**

```java
// rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/context/EvalContextAssembler.java
package com.sstlfsj.rule.kernel.internal.context;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricSourceHandler;
import com.sstlfsj.rule.kernel.api.spi.subject.SubjectLoader;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 装配 EvalContext：SubjectLoader（可选）+ providedMetrics + MetricSourceHandler（可选）。
 * 纯 Java，无 Spring 依赖。
 */
public class EvalContextAssembler {

    private final SubjectLoader subjectLoader;
    private final List<MetricSourceHandler> metricHandlers;

    public EvalContextAssembler(List<SubjectLoader> subjectLoaders,
                                List<MetricSourceHandler> metricHandlers) {
        this.subjectLoader = subjectLoaders == null ? null : subjectLoaders.stream()
                .filter(l -> l.supportedTypes().contains(SubjectType.USER))
                .findFirst()
                .orElse(null);
        this.metricHandlers = metricHandlers == null ? List.of() : List.copyOf(metricHandlers);
    }

    /**
     * 装配一次评估的 EvalContext。
     */
    public EvalContext assemble(RuleEvent event, List<RuleVersionSnapshot> candidates) {
        Subject subject = loadSubject(event);
        Map<String, MetricValue> metrics = new HashMap<>();
        for (Map.Entry<String, Object> entry : event.providedMetrics().entrySet()) {
            metrics.put(entry.getKey(),
                    new MetricValue(entry.getValue(), "UNKNOWN", "PROVIDED"));
        }
        return new EvalContext(event.tenantId(), event, subject, metrics);
    }

    private Subject loadSubject(RuleEvent event) {
        if (subjectLoader == null) {
            return new Subject(event.subjectId(), SubjectType.USER, Map.of());
        }
        try {
            return subjectLoader.load(event.subjectId(), SubjectType.USER, event);
        } catch (Exception e) {
            return new Subject(event.subjectId(), SubjectType.USER, Map.of());
        }
    }
}
```

- [ ] **Step 2: 新建 EvalContextAssemblerTest**

```java
// rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/context/EvalContextAssemblerTest.java
package com.sstlfsj.rule.kernel.internal.context;

import com.sstlfsj.rule.kernel.api.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EvalContextAssemblerTest {

    private static RuleEvent event(Map<String, Object> metrics) {
        return new RuleEvent(UUID.randomUUID().toString(), "t1", "scene1",
                "sub1", "EVT", Map.of(), metrics, null);
    }

    @Test
    void assemble_noSubjectLoader_returnsMinimalSubject() {
        EvalContextAssembler asm = new EvalContextAssembler(List.of(), List.of());
        RuleEvent ev = event(Map.of());
        EvalContext ctx = asm.assemble(ev, List.of());
        assertThat(ctx.subject().subjectId()).isEqualTo("sub1");
    }

    @Test
    void assemble_providedMetrics_areIncludedInContext() {
        EvalContextAssembler asm = new EvalContextAssembler(List.of(), List.of());
        RuleEvent ev = event(Map.of("amount", 1000));
        EvalContext ctx = asm.assemble(ev, List.of());
        assertThat(ctx.metrics()).containsKey("amount");
        assertThat(ctx.metrics().get("amount").value()).isEqualTo(1000);
    }
}
```

- [ ] **Step 3: 运行测试，确认通过**

```bash
$MVN -pl rule-kernel -am test -Dtest='EvalContextAssemblerTest' -Dsurefire.failIfNoSpecifiedTests=false
```

期望：BUILD SUCCESS

- [ ] **Step 4: 删除 rule-eval-svc 中的旧 EvalContextAssembler**

删除文件：`rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/context/EvalContextAssembler.java`

- [ ] **Step 5: 修正 EvalServiceImpl 的 import**

`rule-eval-svc/…/service/EvalServiceImpl.java` 的 import：
```java
// 旧
import com.sstlfsj.rule.eval.internal.context.EvalContextAssembler;
// 新
import com.sstlfsj.rule.kernel.internal.context.EvalContextAssembler;
```

- [ ] **Step 6: 在 EvalAutoConfiguration 注册 EvalContextAssembler Bean**

在 `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/EvalAutoConfiguration.java` 追加：

```java
import com.sstlfsj.rule.kernel.internal.context.EvalContextAssembler;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricSourceHandler;
import com.sstlfsj.rule.kernel.api.spi.subject.SubjectLoader;

// 在类中新增 Bean 方法：
@Bean
public EvalContextAssembler evalContextAssembler(
        @Autowired(required = false) List<SubjectLoader> subjectLoaders,
        @Autowired(required = false) List<MetricSourceHandler> metricHandlers) {
    return new EvalContextAssembler(
            subjectLoaders == null ? List.of() : subjectLoaders,
            metricHandlers == null ? List.of() : metricHandlers);
}
```

- [ ] **Step 7: 运行 rule-eval-svc 全量测试**

```bash
$MVN -pl rule-eval-svc -am test
```

期望：BUILD SUCCESS

- [ ] **Step 8: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/context/ \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/context/ \
        rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/
git commit -m "refactor(kernel): 将 EvalContextAssembler 迁入 rule-kernel，去掉 Spring 注解"
```

---

## Task 3：新建 EvalEngine（rule-kernel）

**目标：** 从 `EvalServiceImpl.doEvaluate()` 提取 matcher → pre-gate → context → executor 编排逻辑，封装为纯 Java `EvalEngine`，无副作用。

**Files:**
- 新建: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/engine/EvalEngine.java`
- 新增测试: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/engine/EvalEngineTest.java`

- [ ] **Step 1: 新建 EvalEngineTest（先写，使用 stub）**

```java
// rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/engine/EvalEngineTest.java
package com.sstlfsj.rule.kernel.internal.engine;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import com.sstlfsj.rule.kernel.api.spi.pregate.PreGate;
import com.sstlfsj.rule.kernel.internal.context.EvalContextAssembler;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EvalEngineTest {

    private static RuleEvent event(String tenant, String scene, String evtType) {
        return new RuleEvent(UUID.randomUUID().toString(), tenant, scene,
                "sub1", evtType, Map.of(), Map.of(), null);
    }

    private static RuleVersionSnapshot snapshot(Long id, String tenant, String scene) {
        return new RuleVersionSnapshot(id, scene, tenant,
                new AndNode(List.of()),
                List.of(),
                List.of(new RuleVersionSnapshot.DecisionBinding("BLOCK", 10)),
                List.of());
    }

    @Test
    void evaluate_noMatchInIndex_returnsMiss() {
        SceneRuleIndex index = new SceneRuleIndex();
        EvalContextAssembler asm = new EvalContextAssembler(List.of(), List.of());
        RuleVersionExecutor executor = (snap, ctx) -> new EvalResult(false, null, List.of(), List.of(), null, List.of());

        EvalEngine engine = new EvalEngine(index, asm, Map.of(), Map.of("AST_BOOLEAN", executor));
        EvalResult result = engine.evaluate(event("t1", "scene", "ORDER"));
        assertThat(result.ruleHit()).isFalse();
    }

    @Test
    void evaluate_matchWithHit_returnsDecision() {
        SceneRuleIndex index = new SceneRuleIndex();
        RuleVersionSnapshot snap = snapshot(1L, "t1", "scene");
        index.update("t1", "scene", "*", List.of(snap));

        EvalContextAssembler asm = new EvalContextAssembler(List.of(), List.of());
        // executor 返回命中
        RuleVersionExecutor executor = (s, ctx) ->
                new EvalResult(true, new Decision("BLOCK", "", 10, s.ruleVersionId()),
                        List.of(new Decision("BLOCK", "", 10, s.ruleVersionId())),
                        List.of(), null, List.of());

        EvalEngine engine = new EvalEngine(index, asm, Map.of(), Map.of("AST_BOOLEAN", executor));
        EvalResult result = engine.evaluate(event("t1", "scene", "ORDER"));
        assertThat(result.ruleHit()).isTrue();
        assertThat(result.decision().decisionCode()).isEqualTo("BLOCK");
    }

    @Test
    void evaluate_preGateBlocks_returnsMiss() {
        SceneRuleIndex index = new SceneRuleIndex();
        RuleVersionSnapshot snap = new RuleVersionSnapshot(1L, "scene", "t1",
                new AndNode(List.of()),
                List.of(new RuleVersionSnapshot.PreGateConfig("ROLLOUT", Map.of("rolloutPct", 0))),
                List.of(new RuleVersionSnapshot.DecisionBinding("BLOCK", 10)),
                List.of());
        index.update("t1", "scene", "*", List.of(snap));

        // gate 总是拦截
        PreGate blockingGate = ctx -> new PreGateResult(false, "ROLLOUT");
        EvalContextAssembler asm = new EvalContextAssembler(List.of(), List.of());
        RuleVersionExecutor executor = (s, ctx) ->
                new EvalResult(true, null, List.of(), List.of(), null, List.of());

        EvalEngine engine = new EvalEngine(index, asm,
                Map.of("ROLLOUT", blockingGate),
                Map.of("AST_BOOLEAN", executor));
        EvalResult result = engine.evaluate(event("t1", "scene", "EVT"));
        assertThat(result.ruleHit()).isFalse();
    }
}
```

- [ ] **Step 2: 确认测试因 EvalEngine 不存在而编译失败**

```bash
$MVN -pl rule-kernel -am test -Dtest='EvalEngineTest' -Dsurefire.failIfNoSpecifiedTests=false
```

期望：COMPILATION ERROR（class EvalEngine not found）

- [ ] **Step 3: 新建 EvalEngine**

`RuleVersionSnapshot` 目前没有 `kind` 字段（D12 Task 4 才加）。Task 3 暂时对所有快照用默认 executor（key `"AST_BOOLEAN"`）；D12 Task 5 再改为按 kind 路由。

```java
// rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/engine/EvalEngine.java
package com.sstlfsj.rule.kernel.internal.engine;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import com.sstlfsj.rule.kernel.api.spi.pregate.PreGate;
import com.sstlfsj.rule.kernel.internal.context.EvalContextAssembler;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;

import java.util.*;

/**
 * 纯 Java 评估编排器：matcher → pre-gate → context → executor。
 * 无副作用，不写 DB，不派发 Action，不依赖 Spring。
 */
public class EvalEngine {

    private static final String DEFAULT_EXECUTOR_KEY = "AST_BOOLEAN";

    private final SceneRuleIndex index;
    private final EvalContextAssembler contextAssembler;
    private final Map<String, PreGate> preGates;
    private final Map<String, RuleVersionExecutor> executors;

    public EvalEngine(SceneRuleIndex index,
                      EvalContextAssembler contextAssembler,
                      Map<String, PreGate> preGates,
                      Map<String, RuleVersionExecutor> executors) {
        this.index = index;
        this.contextAssembler = contextAssembler;
        this.preGates = Map.copyOf(preGates);
        this.executors = Map.copyOf(executors);
    }

    /** 对单个事件求值，返回纯计算结果，无副作用。 */
    public EvalResult evaluate(RuleEvent event) {
        List<RuleVersionSnapshot> candidates =
                index.match(event.tenantId(), event.sceneCode(), event.eventType());
        if (candidates.isEmpty()) return EvalResult.miss();

        List<RuleVersionSnapshot> passed = new ArrayList<>();
        for (RuleVersionSnapshot snap : candidates) {
            if (applyPreGates(event, snap) == null) {
                passed.add(snap);
            }
        }
        if (passed.isEmpty()) return EvalResult.miss();

        EvalContext ctx = contextAssembler.assemble(event, passed);

        List<Decision> hitDecisions = new ArrayList<>();
        List<NodeTrace> allTraces = new ArrayList<>();
        String errorCode = null;

        for (RuleVersionSnapshot snap : passed) {
            try {
                // D12 落地后改为按 snap.kind() 路由；当前使用默认 executor
                RuleVersionExecutor exec = executors.getOrDefault(
                        DEFAULT_EXECUTOR_KEY, executors.values().iterator().next());
                EvalResult r = exec.execute(snap, ctx);
                allTraces.addAll(r.nodeTrace());
                if (r.ruleHit()) {
                    snap.decisionBindings().stream()
                            .max(Comparator.comparingInt(RuleVersionSnapshot.DecisionBinding::priority))
                            .ifPresent(b -> hitDecisions.add(
                                    new Decision(b.decisionCode(), "", b.priority(), snap.ruleVersionId())));
                }
                if (r.errorCode() != null && errorCode == null) errorCode = r.errorCode();
            } catch (Exception e) {
                if (errorCode == null) errorCode = "CONDITION_EVAL_ERROR";
            }
        }

        Decision finalDecision = hitDecisions.stream()
                .max(Comparator.comparingInt(Decision::priority))
                .orElse(null);

        return new EvalResult(
                !hitDecisions.isEmpty(),
                finalDecision,
                List.copyOf(hitDecisions),
                List.copyOf(allTraces),
                errorCode,
                List.of()
        );
    }

    /**
     * 对单条候选快照执行所有 Pre-Gate。
     *
     * @return null 表示全部通过；非 null 为首个阻断的 gate 类型
     */
    private String applyPreGates(RuleEvent event, RuleVersionSnapshot snap) {
        for (RuleVersionSnapshot.PreGateConfig cfg : snap.preGates()) {
            PreGate gate = preGates.get(cfg.gateType());
            if (gate == null) continue;
            PreGateContext ctx = new PreGateContext(
                    event.tenantId(), event.sceneCode(), event.subjectId(),
                    event, snap.ruleVersionId(), cfg.params());
            PreGateResult result = gate.evaluate(ctx);
            if (!result.passed()) return result.blockedBy();
        }
        return null;
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

```bash
$MVN -pl rule-kernel -am test -Dtest='EvalEngineTest' -Dsurefire.failIfNoSpecifiedTests=false
```

期望：BUILD SUCCESS，3 tests passed

- [ ] **Step 5: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/engine/ \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/engine/
git commit -m "feat(kernel): 新增 EvalEngine，提取无副作用评估编排逻辑"
```

---

## Task 4：EvalServiceImpl 变薄 + EvalAutoConfiguration 注册 EvalEngine

**目标：** `EvalServiceImpl` 委托 `EvalEngine` 做纯计算，自身只保留 session 写入、trace 写入、Action 派发等副作用。`EvalAutoConfiguration` 注册 `EvalEngine` Bean。

**Files:**
- 改动: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImpl.java`
- 改动: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/EvalAutoConfiguration.java`
- 测试: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImplTest.java`（已有，扩充）

- [ ] **Step 1: 改 EvalAutoConfiguration，注册 SceneRuleIndex 和 EvalEngine**

```java
// rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/EvalAutoConfiguration.java
package com.sstlfsj.rule.eval;

import com.sstlfsj.rule.eval.internal.action.ActionDispatchService;
import com.sstlfsj.rule.eval.internal.repository.ActionExecutionMapper;
import com.sstlfsj.rule.eval.internal.repository.SceneActionBindingReadMapper;
import com.sstlfsj.rule.kernel.api.annotation.ActionType;
import com.sstlfsj.rule.kernel.api.spi.action.ActionHandler;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricSourceHandler;
import com.sstlfsj.rule.kernel.api.spi.pregate.PreGate;
import com.sstlfsj.rule.kernel.api.spi.subject.SubjectLoader;
import com.sstlfsj.rule.kernel.internal.context.EvalContextAssembler;
import com.sstlfsj.rule.kernel.internal.engine.EvalEngine;
import com.sstlfsj.rule.kernel.internal.evaluator.TracingInterpretedExecutor;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 自动装配规则评估模块。 */
@AutoConfiguration
@ComponentScan("com.sstlfsj.rule.eval.internal")
public class EvalAutoConfiguration {

    @Bean
    public SceneRuleIndex sceneRuleIndex() {
        return new SceneRuleIndex();
    }

    @Bean
    public EvalContextAssembler evalContextAssembler(
            @Autowired(required = false) List<SubjectLoader> subjectLoaders,
            @Autowired(required = false) List<MetricSourceHandler> metricHandlers) {
        return new EvalContextAssembler(
                subjectLoaders == null ? List.of() : subjectLoaders,
                metricHandlers == null ? List.of() : metricHandlers);
    }

    @Bean
    public RuleVersionExecutor ruleVersionExecutor(
            @Autowired(required = false)
            Map<String, com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator> conditionEvaluators) {
        return new TracingInterpretedExecutor(
                conditionEvaluators == null ? Map.of() : conditionEvaluators);
    }

    @Bean
    public EvalEngine evalEngine(
            SceneRuleIndex sceneRuleIndex,
            EvalContextAssembler evalContextAssembler,
            @Autowired(required = false) List<PreGate> preGates,
            RuleVersionExecutor ruleVersionExecutor) {
        Map<String, PreGate> gateMap = new HashMap<>();
        if (preGates != null) {
            preGates.forEach(g -> gateMap.put(g.gateType(), g));
        }
        return new EvalEngine(sceneRuleIndex, evalContextAssembler, gateMap,
                Map.of("AST_BOOLEAN", ruleVersionExecutor));
    }

    @Bean
    public ActionDispatchService actionDispatchService(
            @Autowired(required = false) List<ActionHandler> actionHandlers,
            SceneActionBindingReadMapper bindingMapper,
            ActionExecutionMapper executionMapper) {
        Map<String, ActionHandler> handlerMap = new HashMap<>();
        if (actionHandlers != null) {
            for (ActionHandler handler : actionHandlers) {
                ActionType ann = handler.getClass().getAnnotation(ActionType.class);
                if (ann != null) handlerMap.put(ann.value(), handler);
            }
        }
        return new ActionDispatchService(handlerMap, bindingMapper, executionMapper);
    }
}
```

- [ ] **Step 2: 改 EvalServiceImpl，委托 EvalEngine**

```java
// rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImpl.java
package com.sstlfsj.rule.eval.internal.service;

import com.sstlfsj.rule.eval.api.service.EvalService;
import com.sstlfsj.rule.eval.internal.action.ActionDispatchService;
import com.sstlfsj.rule.eval.internal.dispatch.EvalActionDispatcher;
import com.sstlfsj.rule.eval.internal.session.EvalSessionWriter;
import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.spi.trace.DryRunTraceWriter;
import com.sstlfsj.rule.kernel.api.spi.trace.TraceWriter;
import com.sstlfsj.rule.kernel.internal.engine.EvalEngine;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;

/** EvalService 实现：委托 EvalEngine 做纯计算，负责 session 写入和 Action 派发副作用。 */
@Service
class EvalServiceImpl implements EvalService, InitializingBean, DisposableBean {

    private final EvalEngine evalEngine;
    private final SceneRuleIndex index;
    private final SceneSnapshotLoader snapshotLoader;
    private final EvalSessionWriter sessionWriter;
    private final TraceWriter traceWriter;
    private final DryRunTraceWriter dryRunTraceWriter;
    private final ActionDispatchService actionDispatchService;
    private final EvalActionDispatcher dispatcher;

    EvalServiceImpl(EvalEngine evalEngine,
                    SceneRuleIndex index,
                    SceneSnapshotLoader snapshotLoader,
                    EvalSessionWriter sessionWriter,
                    TraceWriter traceWriter,
                    DryRunTraceWriter dryRunTraceWriter,
                    ActionDispatchService actionDispatchService) {
        this.evalEngine = evalEngine;
        this.index = index;
        this.snapshotLoader = snapshotLoader;
        this.sessionWriter = sessionWriter;
        this.traceWriter = traceWriter;
        this.dryRunTraceWriter = dryRunTraceWriter;
        this.actionDispatchService = actionDispatchService;
        this.dispatcher = new EvalActionDispatcher(10000, this::evaluate);
    }

    @Override
    public void afterPropertiesSet() { dispatcher.start(); }

    @Override
    public void destroy() { dispatcher.stop(); }

    @Override
    public boolean acceptEvent(RuleEvent event) {
        return dispatcher.submit(event);
    }

    @Override
    public EvalResult evaluate(RuleEvent event) {
        return doEvaluate(event, false, null);
    }

    @Override
    public EvalResult dryRun(RuleEvent event, Long ruleVersionId) {
        return doEvaluate(event, true, ruleVersionId);
    }

    private EvalResult doEvaluate(RuleEvent event, boolean isDryRun, Long specificVersionId) {
        if (isDryRun && specificVersionId != null) {
            // dry-run 指定版本：绕过 index，直接从 DB 加载，传给 EvalEngine 重载方法
            RuleVersionSnapshot snap = snapshotLoader.loadById(specificVersionId);
            if (snap == null) return EvalResult.miss();
            EvalResult result = evalEngine.evaluate(event, List.of(snap));
            Long sessionId = sessionWriter.insertDryRunPending(event, specificVersionId);
            sessionWriter.updateDryRunFinal(sessionId, result);
            dryRunTraceWriter.write(event.tenantId(), sessionId.toString(), result.nodeTrace());
            return result;
        }

        // ① 标准评估路径
        List<RuleVersionSnapshot> candidates = index.match(
                event.tenantId(), event.sceneCode(), event.eventType());
        if (candidates.isEmpty()) return EvalResult.miss();

        Long sessionId = sessionWriter.insertPending(event, candidates.size(), "PULL");
        EvalResult result = evalEngine.evaluate(event);

        sessionWriter.updateFinal(sessionId, result);
        traceWriter.write(event.tenantId(), sessionId.toString(), result.nodeTrace());

        if (result.ruleHit()) {
            actionDispatchService.dispatch(sessionId, parseTenantId(event.tenantId()),
                    event.eventId(), event.sceneCode(), result.allDecisions());
        }
        return result;
    }

    private static Long parseTenantId(String tenantId) {
        try { return Long.parseLong(tenantId); }
        catch (NumberFormatException e) { return null; }
    }
}
```

**注意：** dry-run 路径需要用指定快照替换索引来求值。EvalEngine 暴露一个接受快照列表的重载方法即可，无需 getter 穿透模块边界。在 Task 3 的 EvalEngine 中追加一个 `evaluate(RuleEvent, List<RuleVersionSnapshot>)` 重载：

```java
// 在 EvalEngine.java 追加（public，供 rule-eval-svc 的 dry-run 路径调用）
/**
 * 对指定候选快照列表求值，跳过索引查找步骤。
 * 供 dry-run 路径：直接传入从 DB 加载的单条快照。
 */
public EvalResult evaluate(RuleEvent event, List<RuleVersionSnapshot> candidates) {
    if (candidates.isEmpty()) return EvalResult.miss();
    // 复用私有 applyPreGates + context + executor 逻辑
    List<RuleVersionSnapshot> passed = new ArrayList<>();
    for (RuleVersionSnapshot snap : candidates) {
        if (applyPreGates(event, snap) == null) passed.add(snap);
    }
    if (passed.isEmpty()) return EvalResult.miss();
    EvalContext ctx = contextAssembler.assemble(event, passed);
    List<Decision> hitDecisions = new ArrayList<>();
    List<NodeTrace> allTraces = new ArrayList<>();
    String errorCode = null;
    for (RuleVersionSnapshot snap : passed) {
        try {
            RuleVersionExecutor exec = executors.getOrDefault(
                    DEFAULT_EXECUTOR_KEY, executors.values().iterator().next());
            EvalResult r = exec.execute(snap, ctx);
            allTraces.addAll(r.nodeTrace());
            if (r.ruleHit()) {
                snap.decisionBindings().stream()
                        .max(Comparator.comparingInt(RuleVersionSnapshot.DecisionBinding::priority))
                        .ifPresent(b -> hitDecisions.add(
                                new Decision(b.decisionCode(), "", b.priority(), snap.ruleVersionId())));
            }
            if (r.errorCode() != null && errorCode == null) errorCode = r.errorCode();
        } catch (Exception e) {
            if (errorCode == null) errorCode = "CONDITION_EVAL_ERROR";
        }
    }
    Decision finalDecision = hitDecisions.stream()
            .max(Comparator.comparingInt(Decision::priority)).orElse(null);
    return new EvalResult(!hitDecisions.isEmpty(), finalDecision,
            List.copyOf(hitDecisions), List.copyOf(allTraces), errorCode, List.of());
}
```

`EvalServiceImpl` 的 dry-run 路径改为调用此重载，无需临时 SceneRuleIndex，也无需暴露 getter。

- [ ] **Step 3: 运行 rule-eval-svc 全量测试**

```bash
$MVN -pl rule-eval-svc -am test
```

期望：BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/ \
        rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/engine/EvalEngine.java
git commit -m "refactor(eval): EvalServiceImpl 委托 EvalEngine，保留 DB/Action 副作用"
```

---

## Task 5：rule-api 新增 GET /api/v1/sdk/snapshots 端点

**目标：** 提供 SDK 快照拉取端点，供 `SnapshotPoller` 启动加载和增量热更新。复用 `RuleVersionReadMapper.loadActiveByScene()`，响应 `List<RuleVersionSnapshot>`。

**Files:**
- 新增: `rule-api/src/main/java/com/sstlfsj/rule/web/sdk/SdkSnapshotController.java`
- 改动: `rule-api/src/main/java/com/sstlfsj/rule/web/ApiAutoConfiguration.java`
- 改动: `rule-api/pom.xml`（追加 rule-eval-svc 依赖，已有则跳过）
- 测试: `rule-api/src/test/java/com/sstlfsj/rule/web/sdk/SdkSnapshotControllerTest.java`

- [ ] **Step 1: 新建 SdkSnapshotControllerTest（先写，期望 404 失败）**

```java
// rule-api/src/test/java/com/sstlfsj/rule/web/sdk/SdkSnapshotControllerTest.java
package com.sstlfsj.rule.web.sdk;

import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SdkSnapshotController.class)
class SdkSnapshotControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean SceneSnapshotLoader snapshotLoader;

    @Test
    void getSnapshots_declaredScenes_returns200() throws Exception {
        RuleVersionSnapshot snap = new RuleVersionSnapshot(1L, "payment", "t1",
                new AndNode(List.of()), List.of(),
                List.of(new RuleVersionSnapshot.DecisionBinding("BLOCK", 10)),
                List.of("ORDER"));
        when(snapshotLoader.loadByScene(eq("t1"), eq("payment")))
                .thenReturn(Map.of("ORDER", List.of(snap)));

        mockMvc.perform(get("/api/v1/sdk/snapshots")
                        .param("tenantId", "t1")
                        .param("scenes", "payment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].ruleVersionId").value(1));
    }

    @Test
    void getSnapshots_missingTenantId_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/sdk/snapshots"))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: 运行测试，确认失败（404）**

```bash
$MVN -pl rule-api -am test -Dtest='SdkSnapshotControllerTest' -Dsurefire.failIfNoSpecifiedTests=false
```

期望：FAIL（SdkSnapshotController not found）

- [ ] **Step 3: 新建 SdkSnapshotController**

```java
// rule-api/src/main/java/com/sstlfsj/rule/web/sdk/SdkSnapshotController.java
package com.sstlfsj.rule.web.sdk;

import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.web.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** SDK 快照端点：供 SnapshotPoller 启动加载和增量热更新。 */
@RestController
@RequestMapping("/api/v1/sdk")
public class SdkSnapshotController {

    private final SceneSnapshotLoader snapshotLoader;

    public SdkSnapshotController(SceneSnapshotLoader snapshotLoader) {
        this.snapshotLoader = snapshotLoader;
    }

    /**
     * 拉取规则版本快照。
     *
     * @param tenantId 租户 ID（必填）
     * @param scenes   场景编码列表，逗号分隔；不传则加载该租户所有快照
     * @param since    增量拉取时间戳（毫秒），暂不过滤，预留参数
     * @return 快照列表
     */
    @GetMapping("/snapshots")
    public ApiResponse<List<RuleVersionSnapshot>> getSnapshots(
            @RequestParam String tenantId,
            @RequestParam(required = false) String scenes,
            @RequestParam(required = false) Long since) {
        List<RuleVersionSnapshot> result = new ArrayList<>();
        if (scenes != null && !scenes.isBlank()) {
            for (String scene : Arrays.stream(scenes.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList()) {
                Map<String, List<RuleVersionSnapshot>> byType =
                        snapshotLoader.loadByScene(tenantId, scene);
                byType.values().forEach(result::addAll);
            }
        } else {
            Map<String, Map<String, List<RuleVersionSnapshot>>> all =
                    snapshotLoader.loadAll();
            all.values().forEach(inner -> inner.values().forEach(result::addAll));
        }
        // 去重（同一快照可能出现在多个 eventType bucket）
        List<RuleVersionSnapshot> deduped = result.stream()
                .distinct()
                .toList();
        return ApiResponse.ok(deduped);
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

```bash
$MVN -pl rule-api -am test -Dtest='SdkSnapshotControllerTest' -Dsurefire.failIfNoSpecifiedTests=false
```

期望：BUILD SUCCESS，2 tests passed

- [ ] **Step 5: 运行 rule-api 全量测试**

```bash
$MVN -pl rule-api -am test
```

期望：BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add rule-api/src/main/java/com/sstlfsj/rule/web/sdk/ \
        rule-api/src/test/java/com/sstlfsj/rule/web/sdk/
git commit -m "feat(api): 新增 GET /api/v1/sdk/snapshots 端点，供 SnapshotPoller 拉取规则快照"
```

---

## Task 6：新建 rule-sdk 模块

**目标：** 纯 Java 模块，依赖 `rule-kernel`，提供 `FetchMode`、`EvalResultListener`、`EvalSessionListener` SPI、`SnapshotPoller`（HTTP 轮询）、`RuleEngineClient` 门面。

**Files:**
- 新增: `rule-sdk/pom.xml`
- 新增: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/FetchMode.java`
- 新增: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/EvalResultListener.java`
- 新增: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/EvalSessionListener.java`
- 新增: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/SnapshotPoller.java`
- 新增: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/RuleEngineClient.java`
- 改动: `pom.xml`（根 pom 追加 `<module>rule-sdk</module>`）

- [ ] **Step 1: 根 pom.xml 追加 rule-sdk 模块**

在 `pom.xml` 的 `<modules>` 块末尾追加：
```xml
<module>rule-sdk</module>
```

- [ ] **Step 2: 新建 rule-sdk/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.sstlfsj.rule</groupId>
        <artifactId>rule-engine</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>

    <artifactId>rule-sdk</artifactId>
    <packaging>jar</packaging>

    <dependencies>
        <dependency>
            <groupId>com.sstlfsj.rule</groupId>
            <artifactId>rule-kernel</artifactId>
        </dependency>
        <!-- Jackson：反序列化 HTTP 响应的 List<RuleVersionSnapshot> -->
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 3: 新建 SPI 接口和 FetchMode**

```java
// rule-sdk/src/main/java/com/sstlfsj/rule/sdk/FetchMode.java
package com.sstlfsj.rule.sdk;

/** 规则快照订阅模式。 */
public enum FetchMode {
    /** 仅拉取 scenes 配置列表中的 scene。 */
    DECLARED,
    /** 拉取租户下所有 ACTIVE 规则。 */
    ALL,
    /** 首次 evaluate 时按 sceneCode 按需拉取，后台定时刷新。 */
    LAZY
}
```

```java
// rule-sdk/src/main/java/com/sstlfsj/rule/sdk/EvalResultListener.java
package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;

/** 规则命中后回调，业务方自行决定如何处理 Decision。 */
public interface EvalResultListener {
    void onResult(RuleEvent event, EvalResult result);
}
```

```java
// rule-sdk/src/main/java/com/sstlfsj/rule/sdk/EvalSessionListener.java
package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;

/** 可选审计回调，业务方自行决定是否写评估日志。 */
public interface EvalSessionListener {
    void onSession(RuleEvent event, EvalResult result);
}
```

- [ ] **Step 4: 新建 SnapshotPoller**

```java
// rule-sdk/src/main/java/com/sstlfsj/rule/sdk/SnapshotPoller.java
package com.sstlfsj.rule.sdk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 规则快照同步器：启动时全量拉取，后台线程定时增量刷新。
 * 使用 JDK 内置 HttpClient，无额外依赖。
 */
public class SnapshotPoller {

    private final String serverUrl;
    private final String tenantId;
    private final FetchMode fetchMode;
    private final List<String> scenes;
    private final Duration pollInterval;
    private final SceneRuleIndex index;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private ScheduledExecutorService scheduler;

    SnapshotPoller(String serverUrl, String tenantId, FetchMode fetchMode,
                   List<String> scenes, Duration pollInterval, SceneRuleIndex index) {
        this.serverUrl = serverUrl;
        this.tenantId = tenantId;
        this.fetchMode = fetchMode;
        this.scenes = List.copyOf(scenes);
        this.pollInterval = pollInterval;
        this.index = index;
        this.mapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** 启动全量拉取并开启后台轮询线程。 */
    void start() {
        poll();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rule-sdk-poller");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::poll,
                pollInterval.toSeconds(), pollInterval.toSeconds(), TimeUnit.SECONDS);
    }

    /** 停止后台轮询线程。 */
    void stop() {
        if (scheduler != null) scheduler.shutdown();
    }

    private void poll() {
        try {
            String url = buildUrl();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode root = mapper.readTree(resp.body());
                List<RuleVersionSnapshot> snapshots = mapper.convertValue(
                        root.get("data"),
                        new TypeReference<List<RuleVersionSnapshot>>() {});
                refreshIndex(snapshots);
            }
        } catch (Exception e) {
            // 轮询失败不抛出，下次重试（保持最后一次成功的索引状态）
            System.err.println("[SnapshotPoller] 轮询失败: " + e.getMessage());
        }
    }

    private void refreshIndex(List<RuleVersionSnapshot> snapshots) {
        for (RuleVersionSnapshot snap : snapshots) {
            List<String> keys = snap.triggerEventTypes().isEmpty()
                    ? List.of("*") : snap.triggerEventTypes();
            for (String key : keys) {
                index.update(snap.tenantId(), snap.sceneCode(), key, List.of(snap));
            }
        }
    }

    private String buildUrl() {
        StringBuilder url = new StringBuilder(serverUrl)
                .append("/api/v1/sdk/snapshots?tenantId=")
                .append(tenantId);
        if (fetchMode == FetchMode.DECLARED && !scenes.isEmpty()) {
            url.append("&scenes=").append(String.join(",", scenes));
        }
        return url.toString();
    }
}
```

- [ ] **Step 5: 新建 RuleEngineClient**

```java
// rule-sdk/src/main/java/com/sstlfsj/rule/sdk/RuleEngineClient.java
package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import com.sstlfsj.rule.kernel.api.spi.pregate.PreGate;
import com.sstlfsj.rule.kernel.internal.context.EvalContextAssembler;
import com.sstlfsj.rule.kernel.internal.engine.EvalEngine;
import com.sstlfsj.rule.kernel.internal.evaluator.InterpretedExecutor;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 嵌入式规则评估门面。
 * 持有本地 SceneRuleIndex 和 EvalEngine，evaluate() 路径零网络跳转。
 */
public class RuleEngineClient implements AutoCloseable {

    private final EvalEngine evalEngine;
    private final SnapshotPoller poller;
    private final EvalResultListener evalResultListener;
    private final EvalSessionListener evalSessionListener;

    private RuleEngineClient(Builder b) {
        SceneRuleIndex index = new SceneRuleIndex();
        EvalContextAssembler assembler = new EvalContextAssembler(List.of(), List.of());
        RuleVersionExecutor executor = new InterpretedExecutor(Map.of());
        this.evalEngine = new EvalEngine(index, assembler,
                b.preGates != null ? b.preGates : Map.of(),
                Map.of("AST_BOOLEAN", b.executor != null ? b.executor : executor));
        this.poller = new SnapshotPoller(b.serverUrl, b.tenantId, b.fetchMode,
                b.scenes, b.pollInterval, index);
        this.evalResultListener = b.evalResultListener;
        this.evalSessionListener = b.evalSessionListener;
        poller.start();
    }

    /** 对单个事件本地求值，零网络跳转。 */
    public EvalResult evaluate(RuleEvent event) {
        EvalResult result = evalEngine.evaluate(event);
        if (evalResultListener != null) evalResultListener.onResult(event, result);
        if (evalSessionListener != null) evalSessionListener.onSession(event, result);
        return result;
    }

    @Override
    public void close() {
        poller.stop();
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String serverUrl;
        private String tenantId;
        private FetchMode fetchMode = FetchMode.DECLARED;
        private final List<String> scenes = new ArrayList<>();
        private Duration pollInterval = Duration.ofSeconds(30);
        private EvalResultListener evalResultListener;
        private EvalSessionListener evalSessionListener;
        private RuleVersionExecutor executor;
        private Map<String, PreGate> preGates;

        public Builder serverUrl(String v) { this.serverUrl = v; return this; }
        public Builder tenantId(String v) { this.tenantId = v; return this; }
        public Builder fetchMode(FetchMode v) { this.fetchMode = v; return this; }
        public Builder scenes(String... v) { scenes.addAll(Arrays.asList(v)); return this; }
        public Builder pollInterval(Duration v) { this.pollInterval = v; return this; }
        public Builder evalResultListener(EvalResultListener v) { this.evalResultListener = v; return this; }
        public Builder evalSessionListener(EvalSessionListener v) { this.evalSessionListener = v; return this; }
        public Builder executor(RuleVersionExecutor v) { this.executor = v; return this; }
        public Builder preGates(Map<String, PreGate> v) { this.preGates = v; return this; }

        public RuleEngineClient build() {
            if (serverUrl == null || serverUrl.isBlank())
                throw new IllegalArgumentException("serverUrl 必填");
            if (tenantId == null || tenantId.isBlank())
                throw new IllegalArgumentException("tenantId 必填");
            return new RuleEngineClient(this);
        }
    }
}
```

- [ ] **Step 6: 新建 RuleEngineClientTest**

```java
// rule-sdk/src/test/java/com/sstlfsj/rule/sdk/RuleEngineClientTest.java
package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleEngineClientTest {

    @Test
    void build_missingServerUrl_throwsIllegalArgument() {
        assertThatThrownBy(() -> RuleEngineClient.builder()
                .tenantId("t1")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("serverUrl");
    }

    @Test
    void build_missingTenantId_throwsIllegalArgument() {
        assertThatThrownBy(() -> RuleEngineClient.builder()
                .serverUrl("http://localhost:8080")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
    }

    @Test
    void evaluate_emptyIndex_returnsMiss() {
        // SnapshotPoller 会启动后台线程，但无法连接到 localhost，poll 失败静默处理
        // index 为空，evaluate 返回 miss
        try (RuleEngineClient client = RuleEngineClient.builder()
                .serverUrl("http://localhost:19999")  // 不存在的端口
                .tenantId("t1")
                .pollInterval(Duration.ofHours(1))    // 不再重试
                .build()) {
            RuleEvent event = new RuleEvent(UUID.randomUUID().toString(),
                    "t1", "scene1", "sub1", "ORDER", Map.of(), Map.of(), null);
            EvalResult result = client.evaluate(event);
            assertThat(result.ruleHit()).isFalse();
        }
    }

    @Test
    void evaluate_callsEvalResultListener() {
        boolean[] called = {false};
        try (RuleEngineClient client = RuleEngineClient.builder()
                .serverUrl("http://localhost:19999")
                .tenantId("t1")
                .pollInterval(Duration.ofHours(1))
                .evalResultListener((ev, res) -> called[0] = true)
                .build()) {
            RuleEvent event = new RuleEvent(UUID.randomUUID().toString(),
                    "t1", "scene1", "sub1", "ORDER", Map.of(), Map.of(), null);
            client.evaluate(event);
        }
        assertThat(called[0]).isTrue();
    }
}
```

- [ ] **Step 7: 运行 rule-sdk 全量测试**

```bash
$MVN -pl rule-sdk -am test
```

期望：BUILD SUCCESS，4 tests passed

- [ ] **Step 8: Commit**

```bash
git add rule-sdk/ pom.xml
git commit -m "feat(sdk): 新建 rule-sdk 模块，EvalEngine 本地评估 + SnapshotPoller HTTP 轮询"
```

---

## Task 7：新建 rule-sdk-spring-boot-starter 模块

**目标：** 薄 Spring 胶水层，读 `application.yml`，自动装配 `RuleEngineClient` Bean。

**Files:**
- 新增: `rule-sdk-spring-boot-starter/pom.xml`
- 新增: `rule-sdk-spring-boot-starter/src/main/java/com/sstlfsj/rule/sdk/starter/SdkProperties.java`
- 新增: `rule-sdk-spring-boot-starter/src/main/java/com/sstlfsj/rule/sdk/starter/RuleEngineClientAutoConfiguration.java`
- 新增: `rule-sdk-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- 改动: `pom.xml`（根 pom 追加 `<module>rule-sdk-spring-boot-starter</module>`）
- 测试: `rule-sdk-spring-boot-starter/src/test/java/com/sstlfsj/rule/sdk/starter/RuleEngineClientAutoConfigurationTest.java`

- [ ] **Step 1: 根 pom.xml 追加模块**

在 `pom.xml` 的 `<modules>` 追加：
```xml
<module>rule-sdk-spring-boot-starter</module>
```

- [ ] **Step 2: 新建 pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.sstlfsj.rule</groupId>
        <artifactId>rule-engine</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>

    <artifactId>rule-sdk-spring-boot-starter</artifactId>
    <packaging>jar</packaging>

    <dependencies>
        <dependency>
            <groupId>com.sstlfsj.rule</groupId>
            <artifactId>rule-sdk</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-autoconfigure</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-configuration-processor</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 3: 新建 SdkProperties**

```java
// rule-sdk-spring-boot-starter/src/main/java/com/sstlfsj/rule/sdk/starter/SdkProperties.java
package com.sstlfsj.rule.sdk.starter;

import com.sstlfsj.rule.sdk.FetchMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/** rule.sdk.* 配置属性。 */
@ConfigurationProperties(prefix = "rule.sdk")
public class SdkProperties {

    /** rule-api 服务地址，必填。 */
    private String serverUrl;

    /** 租户 ID，必填。 */
    private String tenantId;

    /** 快照拉取模式，默认 DECLARED。 */
    private FetchMode fetchMode = FetchMode.DECLARED;

    /** fetchMode=DECLARED 时要订阅的场景列表。 */
    private List<String> scenes = List.of();

    /** 轮询间隔，默认 30 秒。 */
    private Duration pollInterval = Duration.ofSeconds(30);

    public String getServerUrl() { return serverUrl; }
    public void setServerUrl(String serverUrl) { this.serverUrl = serverUrl; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public FetchMode getFetchMode() { return fetchMode; }
    public void setFetchMode(FetchMode fetchMode) { this.fetchMode = fetchMode; }
    public List<String> getScenes() { return scenes; }
    public void setScenes(List<String> scenes) { this.scenes = scenes; }
    public Duration getPollInterval() { return pollInterval; }
    public void setPollInterval(Duration pollInterval) { this.pollInterval = pollInterval; }
}
```

- [ ] **Step 4: 新建 RuleEngineClientAutoConfiguration**

```java
// rule-sdk-spring-boot-starter/src/main/java/com/sstlfsj/rule/sdk/starter/RuleEngineClientAutoConfiguration.java
package com.sstlfsj.rule.sdk.starter;

import com.sstlfsj.rule.sdk.RuleEngineClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** 自动装配 RuleEngineClient，读取 rule.sdk.* 配置。 */
@AutoConfiguration
@EnableConfigurationProperties(SdkProperties.class)
public class RuleEngineClientAutoConfiguration {

    /**
     * 注册 RuleEngineClient Bean。
     * 业务方注册了自定义 RuleEngineClient Bean 时此 Bean 不生效。
     *
     * @param props rule.sdk.* 配置
     * @return RuleEngineClient 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public RuleEngineClient ruleEngineClient(SdkProperties props) {
        RuleEngineClient.Builder builder = RuleEngineClient.builder()
                .serverUrl(props.getServerUrl())
                .tenantId(props.getTenantId())
                .fetchMode(props.getFetchMode())
                .pollInterval(props.getPollInterval());
        if (props.getScenes() != null) {
            props.getScenes().forEach(s -> builder.scenes(s));
        }
        return builder.build();
    }
}
```

- [ ] **Step 5: 新建 AutoConfiguration.imports**

```
# rule-sdk-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
com.sstlfsj.rule.sdk.starter.RuleEngineClientAutoConfiguration
```

- [ ] **Step 6: 新建 RuleEngineClientAutoConfigurationTest**

```java
// rule-sdk-spring-boot-starter/src/test/java/com/sstlfsj/rule/sdk/starter/RuleEngineClientAutoConfigurationTest.java
package com.sstlfsj.rule.sdk.starter;

import com.sstlfsj.rule.sdk.FetchMode;
import com.sstlfsj.rule.sdk.RuleEngineClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class RuleEngineClientAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RuleEngineClientAutoConfiguration.class));

    @Test
    void autoConfigures_ruleEngineClientBean() {
        runner.withPropertyValues(
                        "rule.sdk.server-url=http://localhost:19999",
                        "rule.sdk.tenant-id=t1",
                        "rule.sdk.fetch-mode=ALL")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(RuleEngineClient.class);
                    RuleEngineClient client = ctx.getBean(RuleEngineClient.class);
                    assertThat(client).isNotNull();
                    client.close();
                });
    }

    @Test
    void backOff_whenBeanAlreadyRegistered() {
        runner.withPropertyValues(
                        "rule.sdk.server-url=http://localhost:19999",
                        "rule.sdk.tenant-id=t1")
                .withBean(RuleEngineClient.class,
                        () -> RuleEngineClient.builder()
                                .serverUrl("http://custom:8080")
                                .tenantId("custom")
                                .build())
                .run(ctx -> assertThat(ctx).hasSingleBean(RuleEngineClient.class));
    }

    @Test
    void declaredMode_scenes_areRespected() {
        runner.withPropertyValues(
                        "rule.sdk.server-url=http://localhost:19999",
                        "rule.sdk.tenant-id=t1",
                        "rule.sdk.fetch-mode=DECLARED",
                        "rule.sdk.scenes=payment,fraud")
                .run(ctx -> {
                    SdkProperties props = ctx.getBean(SdkProperties.class);
                    assertThat(props.getFetchMode()).isEqualTo(FetchMode.DECLARED);
                    assertThat(props.getScenes()).containsExactly("payment", "fraud");
                    ctx.getBean(RuleEngineClient.class).close();
                });
    }
}
```

- [ ] **Step 7: 运行 rule-sdk-spring-boot-starter 全量测试**

```bash
$MVN -pl rule-sdk-spring-boot-starter -am test
```

期望：BUILD SUCCESS，3 tests passed

- [ ] **Step 8: Commit**

```bash
git add rule-sdk-spring-boot-starter/ pom.xml
git commit -m "feat(sdk): 新建 rule-sdk-spring-boot-starter，AutoConfiguration 装配 RuleEngineClient"
```

---

## Task 8：全量验证

**目标：** 所有模块测试通过，确认无编译错误、无测试失败。

- [ ] **Step 1: 全量编译**

```bash
$MVN clean compile
```

期望：BUILD SUCCESS

- [ ] **Step 2: 全量测试**

```bash
$MVN test
```

期望：BUILD SUCCESS，所有模块无 FAILURES / ERRORS

- [ ] **Step 3: Commit（如有最后调整）**

```bash
git add -A
git commit -m "test(d20): 全量验证通过，D20 Embedded SDK 实现完成"
```
