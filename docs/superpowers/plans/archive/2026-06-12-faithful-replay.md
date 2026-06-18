# 忠实重放 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给历史 `evaluation_session` 加忠实重放——锁当时规则版本 + 灌当时 payload/metric/evalNow + 跳过取数,重跑出与当时一致的 `EvalResult`+trace,只读零副作用。

**Architecture:** 落库侧给 `evaluation_session` 加两列(`payload`、`candidate_rule_version_ids`),metric/evalNow 复用现有 `context_snapshot` 列。回放侧:新引擎入口 `EvalEngine.evaluateReplay`(用 degraded `EvalContextAssembler` 把历史 metric 当 providedMetrics 灌入、跳过取数)+ eval-svc `ReplayService`(读 session、按 id 加载历史候选快照、反序列化快照、调引擎)+ rule-api `ReplayController`。**数据落点:不新增表**,两列加在 `evaluation_session`,重放本身不写库。

**Tech Stack:** Java 25 / Spring Boot 4 / MyBatis-Plus / Flyway / rule-kernel(纯 Java) / JUnit5+AssertJ。

**前置:** 跑 Maven 前先用 `mvn-env` skill 设环境;跨模块改动带 `-am`,收尾 `$MVN clean test`。设计依据见 `docs/superpowers/specs/2026-06-12-faithful-replay-design.md` 与 D70。

> **⚠️ 捕获开关前提**:`context_snapshot` 当前由 `AuditProperties.contextSnapshot.enabled` 门控,**默认关**(AuditPersister 行 146-149)。忠实重放依赖 snapshot 存 metric,故本计划把 payload + candidate ids 也纳入**同一开关**,并在 Task 2 把该开关**默认改为开**(对齐"payload 默认存"决策)。这会让每次评估多写 payload/候选 id/snapshot 三段 JSON——若你不接受默认开,执行时把 Task 2 Step 6 的默认值保持 false,届时仅显式开启的部署可重放。

---

## 文件结构

- 新增:`rule-config-svc/src/main/resources/db/migration/V1_31__session_replay_columns.sql`
- 改:`rule-eval-svc/.../internal/domain/EvaluationSession.java`(加 `payload`、`candidateRuleVersionIds` 字段)
- 改:`rule-eval-svc/.../internal/repository/EvaluationSessionMapper.java`(insertBatch 列清单加两列)
- 改:`rule-eval-svc/.../internal/async/AuditRecordedEvent.java`(加 `candidateVersionIds`)
- 改:`rule-eval-svc/.../internal/service/EvalServiceImpl.java`(发事件时带候选版本 id)
- 改:`rule-eval-svc/.../internal/async/AuditPersister.java`(`toSession` 写两列,捕获纳入同一开关)
- 改:`rule-eval-svc/.../internal/async/AuditProperties.java`(捕获开关默认开)
- 改:`rule-kernel/.../internal/engine/EvalEngine.java`(抽 `evaluate0` + 新 `evaluateReplay`)
- 新增:`rule-eval-svc/.../api/service/ReplayService.java` + `internal/service/ReplayServiceImpl.java`
- 新增:`rule-eval-svc/.../internal/async/ContextSnapshotDeserializer.java`(快照反序列化)
- 新增:`rule-api/.../web/admin/ReplayController.java`

---

## Task 1: 迁移 + 实体 + Mapper 加两列

**Files:**
- Create: `rule-config-svc/src/main/resources/db/migration/V1_31__session_replay_columns.sql`
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/domain/EvaluationSession.java`
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/repository/EvaluationSessionMapper.java`

- [ ] **Step 1: 写迁移**

`V1_31__session_replay_columns.sql`:
```sql
-- 忠实重放:存原始 payload 与当时候选规则版本 id 集(均可空,兼容存量行)
ALTER TABLE evaluation_session
    ADD COLUMN payload JSON NULL COMMENT '评估事件原始 payload(忠实重放用)',
    ADD COLUMN candidate_rule_version_ids JSON NULL COMMENT '当时候选规则版本 id 列表(忠实重放用)';
```

- [ ] **Step 2: 实体加字段**

`EvaluationSession.java` 在 `private String contextSnapshot;`(约 `:47`)后加:
```java
    /** 评估事件原始 payload(JSON 文本);忠实重放用,未捕获时 null。 */
    private String payload;
    /** 当时候选规则版本 id 列表(JSON 文本);忠实重放用,未捕获时 null。 */
    private String candidateRuleVersionIds;
```

- [ ] **Step 3: Mapper insertBatch 列清单加两列**

`EvaluationSessionMapper.java` 把 INSERT 的列清单与 VALUES 两处各加两列。列清单末尾 `eval_duration_ms)` 改为:
```
               eval_duration_ms, payload, candidate_rule_version_ids)
```
VALUES 末尾 `#{s.evalDurationMs})` 改为:
```
               #{s.evalDurationMs}, #{s.payload}, #{s.candidateRuleVersionIds})
```

- [ ] **Step 4: 编译验证**

Run: `$MVN -pl rule-eval-svc -am -o test-compile`(若离线不可用去掉 `-o`)
Expected: BUILD SUCCESS

- [ ] **Step 5: 提交**

```bash
git add rule-config-svc/src/main/resources/db/migration/V1_31__session_replay_columns.sql rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/domain/EvaluationSession.java rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/repository/EvaluationSessionMapper.java
git commit -m "feat(eval): add payload + candidate_rule_version_ids columns for replay"
```

---

## Task 2: 落库侧捕获 payload + 候选版本 id

**Files:**
- Modify: `rule-eval-svc/.../internal/async/AuditRecordedEvent.java`
- Modify: `rule-eval-svc/.../internal/service/EvalServiceImpl.java`
- Modify: `rule-eval-svc/.../internal/async/AuditPersister.java`
- Modify: `rule-eval-svc/.../internal/async/AuditProperties.java`
- Test: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/async/AuditPersisterReplayTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.eval.internal.async;

import com.sstlfsj.rule.eval.internal.domain.EvalMode;
import com.sstlfsj.rule.eval.internal.domain.EvaluationSession;
import com.sstlfsj.rule.eval.internal.repository.EvaluationSessionMapper;
import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.spi.trace.TraceWriter;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AuditPersisterReplayTest {

    @Test
    void toSession_writesPayloadAndCandidateIds() throws Exception {
        AtomicReference<List<EvaluationSession>> captured = new AtomicReference<>();
        EvaluationSessionMapper mapper = new EvaluationSessionMapper() {
            @Override public int insertBatch(List<EvaluationSession> list) { captured.set(list); return list.size(); }
        };
        TraceWriter noopTrace = (t, s, nt) -> {};
        ObjectMapper om = new ObjectMapper();
        // captureContextSnapshot=true:走捕获分支
        AuditPersister p = new AuditPersister(10, 10, 50, mapper, noopTrace, om, true);

        RuleEvent ev = RuleEvent.builder().tenantId("1").sceneCode("s").eventType("e")
                .subjectId("u").eventId("evt-1").occurredAt(Instant.now())
                .payload(Map.of("amount", 5000)).source(EventSource.HTTP).build();
        EvalContext ctx = new EvalContext("1", ev, null, Map.of(), Instant.now());
        AuditRecordedEvent e = new AuditRecordedEvent(
                100L, ev, EvalMode.PULL, 2, EvalResult.miss(), ctx, null, 3, List.of(11L, 22L));

        p.afterPropertiesSet();
        p.onAudit(e);
        Thread.sleep(150);
        p.destroy();

        EvaluationSession s = captured.get().get(0);
        assertThat(s.getPayload()).contains("amount").contains("5000");
        assertThat(s.getCandidateRuleVersionIds()).contains("11").contains("22");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-eval-svc -am test -Dtest=AuditPersisterReplayTest`
Expected: 编译失败(`AuditRecordedEvent` 无 `candidateVersionIds` 构造参数 / `getPayload` 不存在)

- [ ] **Step 3: AuditRecordedEvent 加 candidateVersionIds**

`AuditRecordedEvent.java` record 头加末位参数 `List<Long> candidateVersionIds`:
```java
public record AuditRecordedEvent(long sessionId, RuleEvent event, EvalMode mode,
                            int candidateCount, EvalResult result, EvalContext context,
                            String blockedBy, int durationMs, java.util.List<Long> candidateVersionIds)
        implements DomainEvent {
    @Override
    public Durability durability() { return Durability.BEST_EFFORT; }
}
```

- [ ] **Step 4: EvalServiceImpl 发事件带候选版本 id**

`EvalServiceImpl.java` 把 `AuditRecordedEvent` 的发布(约 `:134-135`)改为带候选版本 id:
```java
        eventPublisher.publish(new AuditRecordedEvent(
                sessionId, event, mode, candidates.size(), result, outcome.context(), outcome.blockedBy(), durationMs,
                candidates.stream().map(RuleVersionSnapshot::ruleVersionId).toList()));
```
(`candidates` 在该方法作用域内已有,见 `:114`)

- [ ] **Step 5: AuditPersister.toSession 写两列(纳入同一捕获开关)**

`AuditPersister.java` 把 `toSession`(约 `:146-149`)的 `captureContextSnapshot` 分支扩为同时写三段:
```java
        // 捕获开启才回填重放三件套(payload + 候选版本 id + context_snapshot);默认见 AuditProperties
        if (captureContextSnapshot) {
            s.setContextSnapshot(ContextSnapshotSerializer.serialize(objectMapper, e.context()));
            s.setPayload(serializeJson(e.event().payload()));
            s.setCandidateRuleVersionIds(serializeJson(e.candidateVersionIds()));
        }
```
在类内加私有工具(best-effort,失败写 null):
```java
    private String serializeJson(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException ex) {
            return null;
        }
    }
```

- [ ] **Step 6: AuditProperties 捕获开关默认开**

`AuditProperties.java` 把 `contextSnapshot.enabled` 默认值改为 `true`(若当前为 `false`)。打开 `AuditProperties.java` 找到 `ContextSnapshot` 内部类的 `private boolean enabled = false;`,改为 `private boolean enabled = true;`,并把字段 Javadoc 更新为"重放三件套(payload/候选id/context_snapshot)捕获开关,默认开"。

> 若你选择保持默认关(见计划顶部 ⚠️),本步跳过,默认值留 `false`。

- [ ] **Step 7: 跑测试确认通过**

Run: `$MVN -pl rule-eval-svc -am test -Dtest=AuditPersisterReplayTest`
Expected: PASS

- [ ] **Step 8: rule-eval-svc 全量回归(确认 AuditRecordedEvent 构造点全改到)**

Run: `$MVN -pl rule-eval-svc -am test`
Expected: PASS(若有其它构造 `AuditRecordedEvent` 的测试,按新签名补末位参数 `List.of()`)

- [ ] **Step 9: 提交**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/async/AuditRecordedEvent.java rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImpl.java rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/async/AuditPersister.java rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/async/AuditProperties.java rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/async/AuditPersisterReplayTest.java
git commit -m "feat(eval): capture payload + candidate version ids for replay"
```

---

## Task 3: 引擎回放入口 EvalEngine.evaluateReplay

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/engine/EvalEngine.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/engine/EvalEngineReplayTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.kernel.internal.engine;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricSourceHandler;
import com.sstlfsj.rule.kernel.internal.condition.KernelEvaluators;
import com.sstlfsj.rule.kernel.internal.context.EvalContextAssembler;
import com.sstlfsj.rule.kernel.internal.evaluator.InterpretedExecutor;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import com.sstlfsj.rule.sdk.Condition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class EvalEngineReplayTest {

    @Test
    void evaluateReplay_usesFrozenMetrics_andDoesNotFetch() {
        // 规则:metric total > 100 → HIT;condition 经合成算子读 metric
        AtomicInteger fetchCalls = new AtomicInteger();
        // fetch-enabled 主 assembler:若被调用 fetchCalls++(replay 不应触发)
        // 为简化,本测试主 assembler 用 no-resolver(不 fetch),replay 入口内部自建 degraded assembler,
        // 断言点改为:用冻结 metric=200 命中,而 providedMetrics 缺省时不命中,证明冻结值生效。

        SceneRuleIndex index = new SceneRuleIndex();
        EvalContextAssembler assembler = new EvalContextAssembler(List.of(), List.<MetricSourceHandler>of());
        Map<String, ConditionEvaluator> evals = new java.util.HashMap<>(KernelEvaluators.defaults());
        EvalEngine engine = new EvalEngine(index, assembler, Map.of(),
                Map.of(RuleKind.AST_BOOLEAN.tag(), new InterpretedExecutor(evals)), false);

        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(1L).tenantId("1").sceneCode("s").code("r").version(1L)
                .conditionAst(Condition.gt("total", 100).toAst())
                .addTriggerEventType("e")
                .addDecisionBinding("HIT", 1)
                .addMetricDependency("total", 1)
                .build();

        RuleEvent event = RuleEvent.builder().tenantId("1").sceneCode("s").eventType("e")
                .subjectId("u").eventId("evt-1").occurredAt(Instant.now())
                .payload(Map.of()).source(EventSource.REPLAY).build();

        // 冻结 metric total=200 → 命中
        EvalOutcome hit = engine.evaluateReplay(event, List.of(snap),
                Map.of("total", 200), Instant.now());
        assertThat(hit.result().ruleHit()).isTrue();

        // 冻结 metric total=50 → 不命中(证明用的是回灌值)
        EvalOutcome miss = engine.evaluateReplay(event, List.of(snap),
                Map.of("total", 50), Instant.now());
        assertThat(miss.result().ruleHit()).isFalse();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-kernel -am test -Dtest=EvalEngineReplayTest`
Expected: 编译失败(`evaluateReplay` 不存在)

- [ ] **Step 3: 抽 evaluate0 + 加 evaluateReplay**

`EvalEngine.java`:把 `evaluateWithContext(event, candidates, strategy, now, collectTrace)`(约 `:121-154`)的方法体抽成私有 `evaluate0`,多收一个 `EvalContextAssembler assembler` 参数;`evaluateWithContext` 委托传 `this.contextAssembler`。

把原 `:121-154` 方法体替换为委托:
```java
    public EvalOutcome evaluateWithContext(RuleEvent event, List<RuleVersionSnapshot> candidates,
                                           SceneExecutionStrategy strategy, Instant now,
                                           boolean collectTrace) {
        return evaluate0(event, candidates, strategy, now, collectTrace, contextAssembler);
    }

    /** 回放入口:用 degraded assembler(无取数)+ event.providedMetrics 携带的冻结 metric 评估,
     * 强制收集 trace。与 dry-run 重新取数相反——忠实重现历史 metric。 */
    public EvalOutcome evaluateReplay(RuleEvent event, List<RuleVersionSnapshot> candidates,
                                      Map<String, Object> frozenMetrics, Instant evalNow) {
        RuleEvent replayEvent = event.toBuilder().providedMetrics(frozenMetrics).build();
        SceneExecutionStrategy strategy = index.getStrategy(event.tenantId(), event.sceneCode());
        EvalContextAssembler noFetch = new EvalContextAssembler(List.of(), java.util.List.of());
        return evaluate0(replayEvent, candidates, strategy, evalNow, true, noFetch);
    }

    private EvalOutcome evaluate0(RuleEvent event, List<RuleVersionSnapshot> candidates,
                                  SceneExecutionStrategy strategy, Instant now,
                                  boolean collectTrace, EvalContextAssembler assembler) {
        if (candidates.isEmpty()) return new EvalOutcome(EvalResult.miss(), null);

        List<RuleVersionSnapshot> passed = new ArrayList<>();
        String firstBlockedBy = null;
        for (RuleVersionSnapshot snap : candidates) {
            String blockedBy = applyPreGates(event, snap);
            if (blockedBy == null) passed.add(snap);
            else if (firstBlockedBy == null) firstBlockedBy = blockedBy;
        }
        if (passed.isEmpty()) return new EvalOutcome(EvalResult.miss(), null, firstBlockedBy);

        EvalEnv env = new EvalEnv(now, index.getDefaultParams(event.tenantId(), event.sceneCode()));
        EvalContext ctx = assembler.assemble(event, passed, env);

        EvalResult result;
        try {
            result = ScopedValue.where(TraceScope.COLLECT, collectTrace).call(() -> switch (strategy) {
                case FIRST_HIT -> evaluateFirstHit(event, passed, ctx);
                case HIGHEST_PRIORITY, ALL_HITS -> evaluateAllCandidates(passed, ctx);
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return new EvalOutcome(result, ctx);
    }
```
> 注:`RuleEvent` 有 `@Builder(toBuilder=true)`(D49),`toBuilder().providedMetrics(...)` 可用。degraded `EvalContextAssembler`(resolver=null)把 `providedMetrics` 原值包成 `MetricValue(UNKNOWN, PROVIDED)` 直接进 context、不取数(见 EvalContextAssembler 行 101-108)。

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-kernel -am test -Dtest=EvalEngineReplayTest`
Expected: PASS

- [ ] **Step 5: kernel 全量回归**

Run: `$MVN -pl rule-kernel -am test`
Expected: PASS(确认抽 `evaluate0` 未破坏既有评估)

- [ ] **Step 6: 提交**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/engine/EvalEngine.java rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/engine/EvalEngineReplayTest.java
git commit -m "feat(kernel): add EvalEngine.evaluateReplay with frozen metrics (no fetch)"
```

---

## Task 4: 快照反序列化 + ReplayService

**Files:**
- Create: `rule-eval-svc/.../internal/async/ContextSnapshotDeserializer.java`
- Create: `rule-eval-svc/.../api/service/ReplayService.java`
- Create: `rule-eval-svc/.../internal/service/ReplayServiceImpl.java`
- Test: `rule-eval-svc/.../internal/service/ReplayServiceImplTest.java`

- [ ] **Step 1: 写快照反序列化工具**

`ContextSnapshotDeserializer.java`:
```java
package com.sstlfsj.rule.eval.internal.async;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;

/** 将 context_snapshot JSON({"metrics":{code:rawValue},"evalNow":"<ISO>"})反序列化回 metrics map + evalNow。 */
public final class ContextSnapshotDeserializer {

    private ContextSnapshotDeserializer() {}

    public record Snapshot(Map<String, Object> metrics, Instant evalNow) {}

    /**
     * 反序列化 context_snapshot。
     *
     * @param om   全局 ObjectMapper
     * @param json context_snapshot 列内容
     * @return 解析结果;json 为 null/空/缺字段时对应项为空 map / null
     */
    @SuppressWarnings("unchecked")
    public static Snapshot deserialize(ObjectMapper om, String json) {
        if (json == null || json.isBlank()) return new Snapshot(Map.of(), null);
        Map<String, Object> root = om.readValue(json, new TypeReference<Map<String, Object>>() {});
        Map<String, Object> metrics = root.get("metrics") instanceof Map
                ? (Map<String, Object>) root.get("metrics") : Map.of();
        Object evalNow = root.get("evalNow");
        Instant ts = evalNow != null ? Instant.parse(evalNow.toString()) : null;
        return new Snapshot(metrics, ts);
    }
}
```

- [ ] **Step 2: 写失败测试**

```java
package com.sstlfsj.rule.eval.internal.service;

import com.sstlfsj.rule.eval.api.service.ReplayService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplayServiceImplTest {

    // 用一个最小桩 ReplayServiceImpl:session 缺 payload/snapshot → REPLAY_NOT_REPRODUCIBLE
    @Test
    void replay_missingSnapshot_throwsNotReproducible() {
        ReplayService svc = ReplayTestFixtures.serviceWithSession(
                ReplayTestFixtures.sessionMissingReplayData());
        assertThatThrownBy(() -> svc.replay("1", 100L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("REPLAY_NOT_REPRODUCIBLE");
    }
}
```

> `ReplayTestFixtures` 在 Step 3 与实现一同提供(构造一个注入了桩 mapper/loader/engine 的 `ReplayServiceImpl`)。若不想引 fixtures,可改用 Mockito mock `EvaluationSessionMapper.selectById` 返回缺列的 session,断言抛错。

- [ ] **Step 3: 写 ReplayService 接口 + 实现**

`ReplayService.java`(eval-svc api):
```java
package com.sstlfsj.rule.eval.api.service;

import com.sstlfsj.rule.kernel.api.model.EvalResult;

/** 历史评估会话的忠实重放:锁当时版本 + 灌当时数据 + 跳过取数,只读重跑。 */
public interface ReplayService {
    /**
     * 重放一个历史评估会话。
     *
     * @param tenantId  租户 id(字符串,需可解析为 Long)
     * @param sessionId 评估会话 id
     * @return 与当时一致的评估结果(含 nodeTrace)
     * @throws IllegalArgumentException REPLAY_NOT_REPRODUCIBLE(缺 payload/候选id/snapshot)/ REPLAY_VERSION_MISSING(版本不存在)
     */
    EvalResult replay(String tenantId, Long sessionId);
}
```

`ReplayServiceImpl.java`(eval-svc internal):
```java
package com.sstlfsj.rule.eval.internal.service;

import com.sstlfsj.rule.eval.api.service.ReplayService;
import com.sstlfsj.rule.eval.internal.async.ContextSnapshotDeserializer;
import com.sstlfsj.rule.eval.internal.domain.EvaluationSession;
import com.sstlfsj.rule.eval.internal.repository.EvaluationSessionMapper;
import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.internal.engine.EvalEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 忠实重放实现:读 session → 加载历史候选快照 → 回灌 metric/evalNow/payload → evaluateReplay,零落库。 */
@Service
@RequiredArgsConstructor
public class ReplayServiceImpl implements ReplayService {

    private final EvaluationSessionMapper sessionMapper;
    private final SceneSnapshotLoader snapshotLoader;
    private final EvalEngine evalEngine;
    private final ObjectMapper objectMapper;

    @Override
    public EvalResult replay(String tenantId, Long sessionId) {
        EvaluationSession s = sessionMapper.selectById(sessionId);
        if (s == null || !String.valueOf(s.getTenantId()).equals(tenantId)) {
            throw new IllegalArgumentException("REPLAY_SESSION_NOT_FOUND: " + sessionId);
        }
        if (s.getPayload() == null || s.getCandidateRuleVersionIds() == null || s.getContextSnapshot() == null) {
            throw new IllegalArgumentException(
                    "REPLAY_NOT_REPRODUCIBLE: 缺少 payload/候选版本id/context_snapshot(存量行或捕获未开启)");
        }

        List<Long> candidateIds = objectMapper.readValue(
                s.getCandidateRuleVersionIds(), new TypeReference<List<Long>>() {});
        Map<String, Object> payload = objectMapper.readValue(
                s.getPayload(), new TypeReference<Map<String, Object>>() {});
        ContextSnapshotDeserializer.Snapshot snap =
                ContextSnapshotDeserializer.deserialize(objectMapper, s.getContextSnapshot());

        List<RuleVersionSnapshot> candidates = new ArrayList<>(candidateIds.size());
        for (Long id : candidateIds) {
            RuleVersionSnapshot rv = snapshotLoader.loadById(id);
            if (rv == null) {
                throw new IllegalArgumentException("REPLAY_VERSION_MISSING: ruleVersionId=" + id);
            }
            candidates.add(rv);
        }

        Instant evalNow = snap.evalNow() != null ? snap.evalNow() : Instant.now();
        RuleEvent event = RuleEvent.builder()
                .tenantId(String.valueOf(s.getTenantId()))
                .sceneCode(s.getSceneCode())
                .eventType(s.getEventType())
                .subjectId(s.getSubjectId())
                .eventId(s.getEventId())
                .occurredAt(evalNow)
                .payload(payload)
                .source(EventSource.REPLAY)
                .build();

        // 冻结 metric(snapshot 的 {code:rawValue})作为 providedMetrics 回灌,evaluateReplay 内部 degraded assembler 不取数
        return evalEngine.evaluateReplay(event, candidates, snap.metrics(), evalNow).result();
    }
}
```

> `EvalEngine` 是 kernel 内部类;eval-svc 已在 `EvalServiceImpl` 注入它,故可直接注入(同模块装配)。`sessionMapper.selectById` 来自 `BaseMapper`。

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-eval-svc -am test -Dtest=ReplayServiceImplTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/async/ContextSnapshotDeserializer.java rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/api/service/ReplayService.java rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/service/ReplayServiceImpl.java rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/service/ReplayServiceImplTest.java
git commit -m "feat(eval): add ReplayService with snapshot deserialize and version locking"
```

---

## Task 5: ReplayController 端点

**Files:**
- Create: `rule-api/src/main/java/com/sstlfsj/rule/web/admin/ReplayController.java`
- Test: `rule-api/src/test/java/com/sstlfsj/rule/web/admin/ReplayControllerTest.java`

- [ ] **Step 1: 写失败测试(MockMvc 切片或直调 controller)**

```java
package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.eval.api.service.ReplayService;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayControllerTest {

    @Test
    void replay_delegatesToService() {
        EvalResult expected = EvalResult.hit();
        ReplayService svc = (tenantId, sessionId) -> {
            assertThat(tenantId).isEqualTo("1");
            assertThat(sessionId).isEqualTo(100L);
            return expected;
        };
        ReplayController c = new ReplayController(svc);
        assertThat(c.replay(100L, "1").getData()).isSameAs(expected);
    }
}
```

> `ApiResponse.getData()` 取数据;若访问器名不同(如 `data()`),按 `ApiResponse` 实际访问器调整断言。

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-api -am test -Dtest=ReplayControllerTest`
Expected: 编译失败(`ReplayController` 不存在)

- [ ] **Step 3: 写 ReplayController**

```java
package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.eval.api.service.ReplayService;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.web.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 历史评估会话忠实重放入口。 */
@RestController
@RequestMapping("/admin/v1")
@RequiredArgsConstructor
public class ReplayController {

    private final ReplayService replayService;

    /**
     * POST /admin/v1/evaluation-sessions/{sessionId}/replay — 忠实重放一个历史评估会话。
     *
     * @param sessionId 评估会话 id
     * @param tenantId  租户 id
     * @return 与当时一致的评估结果(含 nodeTrace)
     */
    @PostMapping("/evaluation-sessions/{sessionId}/replay")
    public ApiResponse<EvalResult> replay(
            @PathVariable Long sessionId,
            @RequestParam String tenantId) {
        return ApiResponse.ok(replayService.replay(tenantId, sessionId));
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-api -am test -Dtest=ReplayControllerTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add rule-api/src/main/java/com/sstlfsj/rule/web/admin/ReplayController.java rule-api/src/test/java/com/sstlfsj/rule/web/admin/ReplayControllerTest.java
git commit -m "feat(api): add replay endpoint POST /evaluation-sessions/{id}/replay"
```

---

## Task 6: 端到端功能验证(真服务 + 真落库)

**Files:** 无(手动/脚本验证,遵循 CLAUDE.md 功能测试纪律)

- [ ] **Step 1: 全量编译测试**

Run: `$MVN clean test`
Expected: 全绿

- [ ] **Step 2: 起服务 + 走端到端剧本**

参照 `docs/examples/` 剧本,起 rule-app(确认 V1_31 迁移执行)。按依赖顺序配 scene/decision/metric/rule(含一个 metric 条件),发布。

- [ ] **Step 3: 真实评估 + 核对落库**

`POST /api/v1/rule/evaluate` 评估一个会命中的事件 → 查 `evaluation_session`:确认 `payload`、`candidate_rule_version_ids`、`context_snapshot` **三列真落库**(非 null)。记下 sessionId。

- [ ] **Step 4: 改当前 metric 值,验证重放用历史值**

把该 metric 的当前取数结果改成不同值(换 stub handler 返回值或改底表)→ `POST /admin/v1/evaluation-sessions/{sessionId}/replay?tenantId=...`:
Expected: 返回结果与**当时一致**(用 `context_snapshot` 历史 metric,不是改后的当前值);nodeTrace 与当时一致。

- [ ] **Step 5: 存量行验证不可重放**

手造一条 payload/候选id/snapshot 为 null 的 session(或用本特性前的旧行)→ replay:
Expected: 400 `REPLAY_NOT_REPRODUCIBLE`。

- [ ] **Step 6: 清理测试数据**

删本次新建的 scene/rule/metric/session,恢复干净基线。

---

## Task 7: 文档 D70 + spec 状态

**Files:**
- Modify: `docs/00-decisions.md`(追加 D70)
- Modify: `docs/superpowers/specs/2026-06-12-faithful-replay-design.md`(状态行)

- [ ] **Step 1: 追加 D70**

把 `docs/superpowers/specs/2026-06-12-faithful-replay-design.md` §9 的 D70 条目,作为一行追加到 `docs/00-decisions.md` 汇总表末尾(D62 之后、结尾说明之前),格式对齐现有行 `| D70 | 忠实重放... | A | ... |`。

- [ ] **Step 2: spec 状态置已实现**

把 spec 首部 `> 状态:设计待评审` 改为 `> 状态:已实现`。

- [ ] **Step 3: 提交**

```bash
git add docs/00-decisions.md docs/superpowers/specs/2026-06-12-faithful-replay-design.md
git commit -m "docs: record D70 (faithful replay) and mark design implemented"
```

---

## 自查清单(已核)

- **spec 覆盖**:补两列(T1,§2/§6)/捕获 payload+候选id(T2,§3.1)/引擎回放入口跳过取数(T3,§3.2)/ReplayService 锁版本+回灌(T4,§3.2)/端点(T5,§3.2)/端到端验证(T6,§8)/D70(T7,§9)。
- **类型一致**:`AuditRecordedEvent` 末位加 `List<Long> candidateVersionIds`(T2)与 EvalServiceImpl 发布点(T2)、AuditPersister 读取(T2)一致;`EvalEngine.evaluateReplay(event, candidates, Map<String,Object> frozenMetrics, Instant)`(T3)与 ReplayServiceImpl 调用(T4)一致;`ContextSnapshotDeserializer.Snapshot(metrics,evalNow)`(T4)。
- **API 真实性**:`SceneSnapshotLoader.loadById(Long)`、`EvaluationSessionMapper extends BaseMapper`(`selectById`)、`RuleEvent.toBuilder().providedMetrics(...)`(D49 @Builder)、`EvalContextAssembler` degraded 无取数路径、`ApiResponse.ok`、`@RequiredArgsConstructor` controller 均经源码核对。
- **落点**:重放数据=`evaluation_session` 两新列 + 既有 `context_snapshot`;**不新增表**;重放只读不写库(T4 实现无 insert/update)。
- **捕获开关张力**:已在计划顶部 ⚠️ + T2 Step 6 显式处理(默认开/可保持关)。

---

## 已知不在范围(spec §5 边界 / §1 非目标)

- subject 忠实重现(暂重载当前)、metric dataType/isError 重现、scene 执行策略历史化——均为忠实度边界,本计划不做。
- what-if(历史数据×当前规则)——非目标,后续可复用本设计快照回灌、把 `loadById` 换成取当前 ACTIVE 版本。
