# 配置闭环 B 轮 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补齐从未实装的 D27(decision.actions 接进派发)、修 finalDecision.name 真 bug、砍 scene_metric_binding 与 scene_action_binding 两张 binding 死表,让 action 端到端 tenant 级、与 scene 无关。

**Architecture:** 三阶段按依赖顺序:① 砍 metric binding(独立);② 补齐 D27(发布期把 decision.name/actions 冻进 `rule_version.decision_bindings` 快照 → 评估期 Decision 带 actions → 派发改读 finalDecision.actions);③ 砍 scene_action_binding 整表(派发已不依赖它后整表删除)。`DecisionBinding`/`Decision` 用委托构造扩字段,既有 ~40 处构造点不破。方案甲:快照内嵌 actions,守 D6 不可变 + 评估零额外查询,改 decision 需重发生效。

**Tech Stack:** Java 25 / Spring Boot 4 / Spring Modulith / MyBatis-Plus / Flyway / JUnit5 / Maven 多模块。

> **执行前注意(2026-06-10 追加)**:本 plan 写于 action-best-effort 待办落地之前。action-best-effort 已砍掉 `ActionDispatchService` 的 `ActionIdempotencyGuard`/claim/release,并把当前 `dispatch` 三参化(`handlers/bindingIndex/eventPublisher`)。因此 Task 6/7/8 的派发改造按**当前代码**(已无 guard)执行——去掉 plan 中残留的 guard 相关步骤即可,其余不变。迁移号顺延:V1_21 已被 action-best-effort 占用,本轮 metric binding drop 用 **V1_22**、scene_action_binding drop 用 **V1_23**。

**测试环境(每个 `Run:` 步骤前提)**:先用 `mvn-env` skill 设 `$MVN` / `JAVA_HOME`;跨模块改动带 `-am`;阶段末全量 `$MVN clean test` 兜底。

---

## File Structure

**Phase 1(砍 metric binding)**
- Create `rule-config-svc/src/main/resources/db/migration/V1_21__drop_scene_metric_binding.sql`
- Modify `rule-config-svc/.../internal/service/MetadataServiceImpl.java`(去措辞,无行为变更)

**Phase 2(补齐 D27)**
- Modify `rule-kernel/.../api/model/RuleVersionSnapshot.java`(`DecisionBinding` 扩 name/actions)
- Modify `rule-kernel/.../api/model/Decision.java`(加 actions 字段)
- Modify `rule-kernel/.../internal/engine/EvalEngine.java`(`resolveRuleDecisions` 读 name/actions)
- Modify `rule-config-svc/.../internal/publish/PublishService.java`(冻 decision.name/actions + DECISION_CODE_NOT_FOUND)
- Modify `rule-eval-svc/.../internal/async/DispatchActionsCommand.java`(hitDecisions → finalDecision)
- Modify `rule-eval-svc/.../internal/service/EvalServiceImpl.java`(投递 finalDecision)
- Modify `rule-eval-svc/.../internal/action/ActionDispatchService.java`(读 finalDecision.actions,去 bindingIndex)
- Modify `rule-eval-svc/.../internal/async/InProcessAsyncCommandChannel.java`(调用改 finalDecision)
- Modify `rule-eval-svc/.../EvalAutoConfiguration.java`(去 bindingIndex 入参)
- Create `rule-config-svc/.../api/service/DecisionService.java` + `internal/service/DecisionServiceImpl.java`
- Create `rule-api/.../web/admin/DecisionController.java` + DTO + convert

**Phase 3(砍 scene_action_binding 整表)**
- Create `rule-config-svc/src/main/resources/db/migration/V1_22__drop_scene_action_binding.sql`
- Delete(eval)`SceneActionBindingIndex` / `SceneActionBindingReadMapper` / `SceneActionBindingRow` / `SceneActionBindingFullRow` + tests
- Delete(config)`SceneActionBindingDef` / `SceneActionBindingMapper` / `SceneActionBindingService` / `SceneActionBindingServiceImpl` + tests
- Delete(api)`SceneActionBindingController` / `SceneActionBindingConvert` + tests
- Modify docs `00/01/04/05/06`

---

# Phase 1 — 砍 metric binding(决策二,独立先做)

### Task 1: drop scene_metric_binding 表 + 去 MetadataServiceImpl 白名单措辞

**Files:**
- Create: `rule-config-svc/src/main/resources/db/migration/V1_21__drop_scene_metric_binding.sql`
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/MetadataServiceImpl.java`

> 说明:`scene_metric_binding` 是死表(无实体/Mapper/读写口),`MetadataServiceImpl` 早已绕过白名单查全部 ACTIVE metric。本任务无行为变更,只删表 + 去掉误导性"v1 简化未启用白名单"措辞。验证 = 模块 clean test 无回归。

- [ ] **Step 1: 写迁移文件**

```sql
-- V1_21__drop_scene_metric_binding.sql
-- 砍 metric binding 白名单:metric 在 tenant 级对所有 scene 可用(配置闭环 B 轮决策二)
DROP TABLE IF EXISTS scene_metric_binding;
```

- [ ] **Step 2: 改 MetadataServiceImpl 注释措辞**

打开 `MetadataServiceImpl.java`,定位查 ACTIVE metric 处的注释(原含"v1 简化"/"未启用白名单"/"scene_metric_binding"字样),改为正式口径:

```java
// metric 在 tenant 级对所有 scene 可用(无 scene 级绑定白名单,配置闭环 B 轮决策二)
```

- [ ] **Step 3: 运行模块测试验证无回归**

Run: `$MVN -pl rule-config-svc -am test`
Expected: PASS(BUILD SUCCESS,Flyway 迁移在测试库执行成功,无 scene_metric_binding 相关失败)

- [ ] **Step 4: Commit**

```bash
git add rule-config-svc/src/main/resources/db/migration/V1_21__drop_scene_metric_binding.sql \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/MetadataServiceImpl.java
git commit -m "feat(config): 砍 scene_metric_binding 死表,metric 收敛为 tenant 级可用(B 轮决策二)"
```

---

# Phase 2 — 补齐 D27(决策一)

### Task 2: `DecisionBinding` 快照扩 name/actions(委托构造保兼容)

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/RuleVersionSnapshot.java:59`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/RuleVersionSnapshotTest.java`

- [ ] **Step 1: 写失败测试**

在 `RuleVersionSnapshotTest.java` 追加:

```java
@Test
void decisionBinding_enrichedFieldsAndCompatCtor() {
    var action = new RuleVersionSnapshot.DecisionAction("a1", "SEND_ALERT", 0, java.util.Map.of("ch", "sms"));
    var enriched = new RuleVersionSnapshot.DecisionBinding("REJECT", "拒绝", 10, java.util.List.of(action));
    assertThat(enriched.decisionCode()).isEqualTo("REJECT");
    assertThat(enriched.name()).isEqualTo("拒绝");
    assertThat(enriched.priority()).isEqualTo(10);
    assertThat(enriched.actions()).containsExactly(action);

    // 兼容构造:旧 (code, priority) 调用点 name=null、actions 空
    var compat = new RuleVersionSnapshot.DecisionBinding("PASS", 1);
    assertThat(compat.name()).isNull();
    assertThat(compat.actions()).isEmpty();
}
```

- [ ] **Step 2: 运行验证失败**

Run: `$MVN -pl rule-kernel test -Dtest=RuleVersionSnapshotTest#decisionBinding_enrichedFieldsAndCompatCtor`
Expected: FAIL(编译错误:构造器 `DecisionBinding(String,String,int,List)` 不存在)

- [ ] **Step 3: 改 DecisionBinding record**

替换 `RuleVersionSnapshot.java:59` 的 `public record DecisionBinding(String decisionCode, int priority) {}`:

```java
    /**
     * Decision 绑定配置快照。发布期从 decision_definition 冻结 name/actions 进来(方案甲,守 D6)。
     *
     * @param decisionCode decision 编码
     * @param name         decision 名称(发布期冻结;旧兼容构造为 null)
     * @param priority     绑定优先级,越大越优先
     * @param actions      decision 的 action 列表(发布期冻结;旧兼容构造为空)
     */
    public record DecisionBinding(String decisionCode, String name, int priority, List<DecisionAction> actions) {
        public DecisionBinding {
            actions = actions == null ? List.of() : List.copyOf(actions);
        }

        /** 兼容旧调用点:仅 (decisionCode, priority),name=null、actions 空。 */
        public DecisionBinding(String decisionCode, int priority) {
            this(decisionCode, null, priority, List.of());
        }
    }
```

- [ ] **Step 4: 运行验证通过**

Run: `$MVN -pl rule-kernel test -Dtest=RuleVersionSnapshotTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/RuleVersionSnapshot.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/RuleVersionSnapshotTest.java
git commit -m "feat(kernel): DecisionBinding 快照扩 name/actions,委托构造保兼容(D27 补齐)"
```

---

### Task 3: `Decision` 模型加 actions 字段(委托构造保兼容)

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/Decision.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/DecisionTest.java`(若不存在则 Create)

- [ ] **Step 1: 写失败测试**

Create/追加 `DecisionTest.java`:

```java
package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class DecisionTest {
    @Test
    void carriesActions_andCompatCtorsDefaultEmpty() {
        var action = new RuleVersionSnapshot.DecisionAction("a1", "SEND_ALERT", 0, Map.of());
        var full = new Decision("REJECT", "拒绝", 10, 7L, "CAT", List.of(action));
        assertThat(full.actions()).containsExactly(action);
        assertThat(full.category()).isEqualTo("CAT");

        // 4-arg 兼容构造:actions 空、category null
        var compat4 = new Decision("PASS", "", 1, 7L);
        assertThat(compat4.actions()).isEmpty();
        assertThat(compat4.category()).isNull();

        // 5-arg 兼容构造(带 category):actions 空
        var compat5 = new Decision("R", "n", 2, 7L, "C");
        assertThat(compat5.actions()).isEmpty();
    }
}
```

- [ ] **Step 2: 运行验证失败**

Run: `$MVN -pl rule-kernel test -Dtest=DecisionTest`
Expected: FAIL(编译错误:`Decision` 无 6-arg 构造、无 `actions()`)

- [ ] **Step 3: 改 Decision record**

替换整个 `Decision.java`:

```java
package com.sstlfsj.rule.kernel.api.model;

import java.util.List;

/** 规则命中后的决策描述,priority 越大越优先。category 为 DECISION_TREE 命中叶子的分类标签,其他 kind 为 null。
 *  actions 为 D27 决策挂载的动作列表,由发布期从 decision_definition 冻结进快照、评估期回填,派发期消费。 */
public record Decision(
        String code,
        String name,
        int priority,
        Long fromRuleVersionId,
        String category,
        List<RuleVersionSnapshot.DecisionAction> actions
) {
    public Decision {
        actions = actions == null ? List.of() : List.copyOf(actions);
    }

    /** 带 category、无 actions 的便捷构造(actions 空)。 */
    public Decision(String code, String name, int priority, Long fromRuleVersionId, String category) {
        this(code, name, priority, fromRuleVersionId, category, List.of());
    }

    /** 无分类、无 actions 的便捷构造(category=null,actions 空)。 */
    public Decision(String code, String name, int priority, Long fromRuleVersionId) {
        this(code, name, priority, fromRuleVersionId, null, List.of());
    }
}
```

- [ ] **Step 4: 运行验证通过**

Run: `$MVN -pl rule-kernel test -Dtest=DecisionTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/Decision.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/DecisionTest.java
git commit -m "feat(kernel): Decision 加 actions 字段,委托构造保兼容(D27 补齐)"
```

---

### Task 4: `resolveRuleDecisions` 读 binding 的 name/actions(修 finalDecision.name 真 bug)

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/engine/EvalEngine.java:237`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/engine/EvalEngineTest.java`

> 修 EvalEngine:237 `new Decision(code, "", priority, ...)` 硬塞空串。改为读 binding.name()/actions()。同时覆盖 `evaluateFirstHit`(走同一 `resolveRuleDecisions`)。

- [ ] **Step 1: 写失败测试**

在 `EvalEngineTest.java` 追加(命中后 finalDecision.name 非空、actions 透传):

```java
@Test
void finalDecisionCarriesNameAndActionsFromBinding() {
    var action = new RuleVersionSnapshot.DecisionAction("a1", "SEND_ALERT", 0, java.util.Map.of());
    var binding = new RuleVersionSnapshot.DecisionBinding("REJECT", "拒绝", 10, java.util.List.of(action));
    // 一条恒真规则,绑定上面的富 binding
    RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
            .ruleVersionId(1L).sceneCode("S").tenantId("9001")
            .conditionAst(alwaysTrueAst())   // 复用本测试类既有恒真 AST 构造工具
            .build();
    // builder 的 addDecisionBinding 只接受 (code, priority),这里直接用富 binding 重建快照
    snap = new RuleVersionSnapshot(1L, "S", "9001", snap.conditionAst(),
            java.util.List.of(), java.util.List.of(binding), java.util.List.of(), "AST_BOOLEAN");

    EvalResult r = newEngine().evaluate(sampleEvent(), java.util.List.of(snap), java.time.Instant.now());

    assertThat(r.finalDecision()).isNotNull();
    assertThat(r.finalDecision().name()).isEqualTo("拒绝");          // 不再是空串
    assertThat(r.finalDecision().actions()).containsExactly(action); // actions 透传
}
```

> 注:`alwaysTrueAst()` / `newEngine()` / `sampleEvent()` 用本测试类既有辅助方法;若命名不同,照同文件现有命中用例的写法对齐(参考 `EvalEngineTest:37` 处构造 BLOCK binding 的命中测试)。

- [ ] **Step 2: 运行验证失败**

Run: `$MVN -pl rule-kernel test -Dtest=EvalEngineTest#finalDecisionCarriesNameAndActionsFromBinding`
Expected: FAIL(name 为 ""、actions 为空)

- [ ] **Step 3: 改 resolveRuleDecisions**

替换 `EvalEngine.java:237` 的 `return List.of(new Decision(best.decisionCode(), "", best.priority(), snap.ruleVersionId()));`:

```java
        return List.of(new Decision(best.decisionCode(), best.name(), best.priority(),
                snap.ruleVersionId(), null, best.actions()));
```

- [ ] **Step 4: 运行验证通过**

Run: `$MVN -pl rule-kernel test -Dtest=EvalEngineTest`
Expected: PASS

- [ ] **Step 5: 跑 kernel 全量(覆盖 FIRST_HIT/strategy 等同路径)**

Run: `$MVN -pl rule-kernel test`
Expected: PASS(`EvalEngineStrategyTest` 等走 `resolveRuleDecisions` 的用例全绿)

- [ ] **Step 6: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/engine/EvalEngine.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/engine/EvalEngineTest.java
git commit -m "fix(kernel): finalDecision 从 binding 读 name/actions,修 name 永远空串 bug(D27 补齐)"
```

---

### Task 5: 发布期冻 decision.name/actions 进快照 + DECISION_CODE_NOT_FOUND 校验

**Files:**
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PublishService.java`
- Test: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/PublishServiceTest.java`

> 发布期:遍历 draftVersion 的 decisionBindings,对每个 decisionCode 查 `decision_definition`;不存在 → 拒绝(message 含 `DECISION_CODE_NOT_FOUND`);存在 → 用 decision.name/actions 重建富 binding,setDecisionBindings 落库。`DecisionDefinitionMapper.findByCodes` 已存在。

- [ ] **Step 1: 写失败测试**

在 `PublishServiceTest.java` 追加两例(沿用该测试类既有 mock 风格:mock `DecisionDefinitionMapper`):

```java
@Test
void publish_rejectsWhenDecisionCodeNotFound() {
    // draft 绑定 REJECT,但 decision_definition 查不到
    stubDraftWithBindings(List.of(new RuleVersionSnapshot.DecisionBinding("REJECT", 10)));
    when(decisionDefinitionMapper.findByCodes(eq(9001L), anyCollection())).thenReturn(List.of());

    assertThatThrownBy(() -> publishService.publish(9001L, RULE_ID, "actor"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("DECISION_CODE_NOT_FOUND");
}

@Test
void publish_freezesDecisionNameAndActionsIntoSnapshot() {
    stubDraftWithBindings(List.of(new RuleVersionSnapshot.DecisionBinding("REJECT", 10)));
    var dd = new DecisionDefinition();
    dd.setTenantId(9001L); dd.setCode("REJECT"); dd.setName("拒绝");
    dd.setActions(List.of(new RuleVersionSnapshot.DecisionAction("a1", "SEND_ALERT", 0, Map.of())));
    when(decisionDefinitionMapper.findByCodes(eq(9001L), anyCollection())).thenReturn(List.of(dd));

    publishService.publish(9001L, RULE_ID, "actor");

    // 捕获落库的 RuleVersion,断言 decisionBindings 已被富化
    ArgumentCaptor<RuleVersion> cap = ArgumentCaptor.forClass(RuleVersion.class);
    verify(ruleVersionMapper).insert(cap.capture());
    var b = cap.getValue().getDecisionBindings().getFirst();
    assertThat(b.name()).isEqualTo("拒绝");
    assertThat(b.actions()).hasSize(1);
    assertThat(b.actions().getFirst().actionType()).isEqualTo("SEND_ALERT");
}
```

> `stubDraftWithBindings(...)` 为本任务在测试类内新增的私有辅助:按该类既有 `publish` 成功用例的 mock 链(ruleDefinitionMapper/sceneMapper/ruleVersionMapper.findLatestDraft 等)装配,只把 draftVersion 的 decisionBindings 替换为入参。照搬现有成功用例的 stub 即可。

- [ ] **Step 2: 运行验证失败**

Run: `$MVN -pl rule-config-svc -am test -Dtest=PublishServiceTest#publish_rejectsWhenDecisionCodeNotFound+publish_freezesDecisionNameAndActionsIntoSnapshot`
Expected: FAIL(当前 PublishService 不查 decision_definition,不富化,REJECT 也不拒绝)

- [ ] **Step 3: 注入 Mapper + 实现富化校验**

在 `PublishService` 字段区(line 41 后)加注入:

```java
    private final DecisionDefinitionMapper decisionDefinitionMapper;
```

在 `publish(...)` 内 `newRv.setDecisionBindings(...)`(当前 line 186-187)**替换**为富化逻辑:

```java
        // D27:发布期冻结 decision.name/actions 进 binding 快照(方案甲,守 D6);decisionCode 必须存在
        List<RuleVersionSnapshot.DecisionBinding> rawBindings = draftVersion.getDecisionBindings() != null
                ? draftVersion.getDecisionBindings() : java.util.List.of();
        newRv.setDecisionBindings(freezeDecisionBindings(tenantId, rawBindings));
```

在类内新增私有方法:

```java
    /**
     * 把 draft 的 (decisionCode, priority) binding 富化为含 name/actions 的快照 binding。
     * 引用的 decisionCode 必须在 decision_definition 存在,否则拒绝发布(DECISION_CODE_NOT_FOUND)。
     */
    private List<RuleVersionSnapshot.DecisionBinding> freezeDecisionBindings(
            Long tenantId, List<RuleVersionSnapshot.DecisionBinding> rawBindings) {
        if (rawBindings.isEmpty()) return java.util.List.of();
        java.util.List<String> codes = rawBindings.stream()
                .map(RuleVersionSnapshot.DecisionBinding::decisionCode).distinct().toList();
        Map<String, DecisionDefinition> byCode = decisionDefinitionMapper.findByCodes(tenantId, codes).stream()
                .collect(Collectors.toMap(DecisionDefinition::getCode, d -> d, (a, b) -> a));
        java.util.List<RuleVersionSnapshot.DecisionBinding> frozen = new ArrayList<>(rawBindings.size());
        for (RuleVersionSnapshot.DecisionBinding b : rawBindings) {
            DecisionDefinition d = byCode.get(b.decisionCode());
            if (d == null) {
                throw new IllegalArgumentException(
                        "DECISION_CODE_NOT_FOUND: 引用的 decision 不存在: " + b.decisionCode());
            }
            frozen.add(new RuleVersionSnapshot.DecisionBinding(
                    b.decisionCode(), d.getName(), b.priority(),
                    d.getActions() != null ? d.getActions() : java.util.List.of()));
        }
        return frozen;
    }
```

> import 补:`com.sstlfsj.rule.config.internal.domain.DecisionDefinition` 已在 `internal.domain.*`(line 5 通配)覆盖;`DecisionDefinitionMapper` 在 `internal.repository.*`(line 9 通配)覆盖。无需新增 import。

- [ ] **Step 4: 运行验证通过**

Run: `$MVN -pl rule-config-svc -am test -Dtest=PublishServiceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PublishService.java \
        rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/PublishServiceTest.java
git commit -m "feat(config): 发布期冻 decision.name/actions 进快照 + DECISION_CODE_NOT_FOUND 校验(D27 补齐)"
```

---

### Task 6: `DispatchActionsCommand` 改携带 finalDecision

**Files:**
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/async/DispatchActionsCommand.java`
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImpl.java:99-102`
- Test: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImplTest.java`

> D27:只派发 finalDecision 的 actions(hitDecisions 里其他 decision 不派发)。命令从携带 `List<Decision> hitDecisions` 改为单个 `Decision finalDecision`。

- [ ] **Step 1: 写失败测试**

在 `EvalServiceImplTest.java` 追加(命中后投递的命令携带 finalDecision):

```java
@Test
void deliversCommandWithFinalDecision() {
    // 沿用本类既有命中用例装配(scene/rule/decisionBinding),断言 actionDelivery 收到的命令 finalDecision 非空
    ArgumentCaptor<DispatchActionsCommand> cap = ArgumentCaptor.forClass(DispatchActionsCommand.class);
    // ... 触发一次命中评估(照本类既有 hit 用例)...
    verify(actionDelivery).deliver(cap.capture());
    assertThat(cap.getValue().finalDecision()).isNotNull();
    assertThat(cap.getValue().finalDecision().code()).isEqualTo(decisionCode);
}
```

> 照本测试类既有命中用例(`EvalServiceImplTest:53` 构造 `DecisionBinding(decisionCode, 10)` 的那条)装配 mock,只把断言换成对命令 finalDecision 的捕获。

- [ ] **Step 2: 运行验证失败**

Run: `$MVN -pl rule-eval-svc -am test -Dtest=EvalServiceImplTest#deliversCommandWithFinalDecision`
Expected: FAIL(编译错误:`DispatchActionsCommand` 无 `finalDecision()`)

- [ ] **Step 3: 改命令 record**

替换 `DispatchActionsCommand.java` 的 record 定义:

```java
/**
 * action 派发命令:触发「去执行这次命中的 finalDecision 的 action」。本期经 {@link ActionCommandChannel}
 * 进程内异步投递(best-effort);实现 {@link Serializable} 以便将来换 MQ 投递时序列化。
 *
 * @param sessionId     评估会话 id
 * @param tenantId      租户 id
 * @param eventId       业务事件 id(幂等键,供 handler 去重)
 * @param sceneCode     场景编码
 * @param finalDecision 本次合成的最终决策(携带 actions),仅它的 action 被派发
 */
public record DispatchActionsCommand(long sessionId, long tenantId, String eventId,
                                     String sceneCode, Decision finalDecision) implements Serializable {}
```

> import 保留 `com.sstlfsj.rule.kernel.api.model.Decision`;删 `java.util.List`(不再用)。

- [ ] **Step 4: 改 EvalServiceImpl 投递逻辑**

替换 `EvalServiceImpl.java:99-102`:

```java
        if (tid != null && result.finalDecision() != null
                && !result.finalDecision().actions().isEmpty()) {
            actionDelivery.deliver(new DispatchActionsCommand(
                    sessionId, tid, event.eventId(), event.sceneCode(), result.finalDecision()));
        }
```

- [ ] **Step 5: 运行验证通过**

Run: `$MVN -pl rule-eval-svc -am test -Dtest=EvalServiceImplTest#deliversCommandWithFinalDecision`
Expected: PASS(其余 EvalServiceImplTest 用例可能因命令改型暂红,Task 7 修派发后整体转绿)

- [ ] **Step 6: Commit**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/async/DispatchActionsCommand.java \
        rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImpl.java \
        rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImplTest.java
git commit -m "feat(eval): 派发命令改携带 finalDecision,只派发合成决策(D27 补齐)"
```

---

### Task 7: `ActionDispatchService` 改读 finalDecision.actions,去 SceneActionBindingIndex

**Files:**
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/action/ActionDispatchService.java`
- Test: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/action/ActionDispatchServiceTest.java`(若不存在则 Create)

> 派发改造核心:遍历 `finalDecision.actions()`,逐个 action 用其 actionType 找 handler、params 取 `DecisionAction.params()`;handler 找不到 → `ActionResult.skipped(NO_HANDLER)`。去掉 `SceneActionBindingIndex` 依赖与 `scene_action_binding` 笛卡尔积。`idempotencyGuard` 本轮保留(best-effort 轮再砍)。

- [ ] **Step 1: 写失败测试**

Create/追加 `ActionDispatchServiceTest.java`:

```java
package com.sstlfsj.rule.eval.internal.action;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionAction;
import com.sstlfsj.rule.kernel.api.spi.action.ActionHandler;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ActionDispatchServiceTest {

    @Test
    void dispatchesFinalDecisionActions_paramsFromAction() {
        ActionHandler handler = mock(ActionHandler.class);
        when(handler.execute(any())).thenReturn(ActionResult.success("a1", "SEND_ALERT"));
        var publisher = new RecordingPublisher();
        var svc = new ActionDispatchService(Map.of("SEND_ALERT", handler), publisher, alwaysClaimGuard());

        var action = new DecisionAction("a1", "SEND_ALERT", 0, Map.of("ch", "sms"));
        var fd = new Decision("REJECT", "拒绝", 10, 7L, null, List.of(action));
        svc.dispatch(1L, 9001L, "evt-1", "S", fd);

        ArgumentCaptor<ActionContext> ctx = ArgumentCaptor.forClass(ActionContext.class);
        verify(handler).execute(ctx.capture());
        assertThat(ctx.getValue().params()).containsEntry("ch", "sms");   // params 取自 decision.action
        assertThat(publisher.events).hasSize(1);                          // 每个 action 发一条 ActionExecutedEvent
    }

    @Test
    void skipsWhenNoHandler() {
        var publisher = new RecordingPublisher();
        var svc = new ActionDispatchService(Map.of(), publisher, alwaysClaimGuard());
        var fd = new Decision("REJECT", "拒绝", 10, 7L, null,
                List.of(new DecisionAction("a1", "UNKNOWN", 0, Map.of())));
        svc.dispatch(1L, 9001L, "evt-1", "S", fd);
        assertThat(publisher.events.getFirst().result().status())
                .isEqualTo(ActionResult.ActionStatus.SKIPPED);
    }
    // RecordingPublisher / alwaysClaimGuard():照本包既有测试桩写法(DomainEventPublisher 收集、guard 恒 claim 成功)
}
```

> `ActionResult.success(...)` / `ActionResult.skipped(...)` 用 kernel 既有工厂(见 `ActionDispatchService` 原 `executeHandler` 用的 `ActionResult.skipped(actionId, actionType, "NO_HANDLER")`)。`RecordingPublisher`/`alwaysClaimGuard` 为测试内简单桩。

- [ ] **Step 2: 运行验证失败**

Run: `$MVN -pl rule-eval-svc -am test -Dtest=ActionDispatchServiceTest`
Expected: FAIL(编译错误:`ActionDispatchService` 构造仍要 4 参含 bindingIndex;`dispatch` 仍收 List)

- [ ] **Step 3: 重写 ActionDispatchService**

替换整个 `dispatch` + `executeHandler` + 构造 + 字段:

```java
    private final Map<String, ActionHandler> handlers;
    private final DomainEventPublisher eventPublisher;
    private final ActionIdempotencyGuard idempotencyGuard;

    public ActionDispatchService(Map<String, ActionHandler> handlers,
                                 DomainEventPublisher eventPublisher,
                                 ActionIdempotencyGuard idempotencyGuard) {
        this.handlers = handlers;
        this.eventPublisher = eventPublisher;
        this.idempotencyGuard = idempotencyGuard;
    }

    /**
     * 派发 finalDecision 挂载的 action(D27):逐个 action 执行 handler、发 ActionExecutedEvent。
     * 落库由 ActionExecutionPersister 异步消费。仅 finalDecision 的 action 被派发。
     *
     * @param sessionId     评估会话 id
     * @param tenantId      租户 id
     * @param eventId       业务事件 id(幂等唯一键)
     * @param sceneCode     场景编码
     * @param finalDecision 合成的最终决策(携带 actions)
     */
    public void dispatch(Long sessionId, Long tenantId, String eventId,
                         String sceneCode, Decision finalDecision) {
        if (finalDecision == null) return;
        for (RuleVersionSnapshot.DecisionAction action : finalDecision.actions()) {
            String actionId = action.actionId();
            String key = tenantId + ":" + eventId + ":" + finalDecision.code() + ":" + actionId;
            if (!idempotencyGuard.claim(key)) {
                log.debug("action 幂等跳过 key={}", key);
                continue;
            }
            ActionResult result = executeHandler(action, finalDecision);
            if (result.status() == ActionResult.ActionStatus.FAILED) {
                idempotencyGuard.release(key);
            }
            eventPublisher.publish(new ActionExecutedEvent(
                    sessionId, tenantId, eventId, actionId, action.actionType(),
                    finalDecision.code(), result));
        }
    }

    private ActionResult executeHandler(RuleVersionSnapshot.DecisionAction action, Decision finalDecision) {
        ActionHandler handler = handlers.get(action.actionType());
        if (handler == null) {
            return ActionResult.skipped(action.actionId(), action.actionType(), "NO_HANDLER");
        }
        Map<String, Object> params = action.params() != null ? action.params() : Map.of();
        ActionContext ctx = new ActionContext(action.actionId(), action.actionType(),
                params, null, null, finalDecision.code());
        return handler.execute(ctx);
    }
```

> import:删 `SceneActionBindingRow`;加 `com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot`。`SceneActionBindingIndex` 字段/入参删除。

- [ ] **Step 4: 运行验证通过**

Run: `$MVN -pl rule-eval-svc -am test -Dtest=ActionDispatchServiceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/action/ActionDispatchService.java \
        rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/action/ActionDispatchServiceTest.java
git commit -m "feat(eval): 派发改读 finalDecision.actions,去 scene_action_binding 笛卡尔积(D27 补齐)"
```

---

### Task 8: 接通 channel 调用 + EvalAutoConfiguration 去 bindingIndex

**Files:**
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/async/InProcessAsyncCommandChannel.java:75-76`
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/EvalAutoConfiguration.java:203-217`

- [ ] **Step 1: 改 channel flushBatch 调用**

替换 `InProcessAsyncCommandChannel.java:75-76`:

```java
                dispatchService.dispatch(e.sessionId(), e.tenantId(), e.eventId(),
                        e.sceneCode(), e.finalDecision());
```

- [ ] **Step 2: 改 EvalAutoConfiguration 去掉 bindingIndex 入参**

替换 `actionDispatchService` Bean 方法签名与返回(去 `SceneActionBindingIndex bindingIndex` 入参、去注释里的 `@param bindingIndex`、`new ActionDispatchService(handlerMap, eventPublisher, idempotencyGuard)`):

```java
    @Bean
    public ActionDispatchService actionDispatchService(
            @Autowired(required = false) List<ActionHandler> actionHandlers,
            DomainEventPublisher eventPublisher,
            ActionIdempotencyGuard idempotencyGuard) {
        Map<String, ActionHandler> handlerMap = new HashMap<>();
        if (actionHandlers != null) {
            for (ActionHandler handler : actionHandlers) {
                ActionType ann = handler.getClass().getAnnotation(ActionType.class);
                if (ann != null) {
                    handlerMap.put(ann.value(), handler);
                }
            }
        }
        return new ActionDispatchService(handlerMap, eventPublisher, idempotencyGuard);
    }
```

> 若 `SceneActionBindingIndex` 在该配置类别处还有 `@Bean`,**暂留**(Phase 3 Task 12 整体删除);本任务只断开 dispatch 对它的依赖。

- [ ] **Step 3: 运行 eval 模块全量**

Run: `$MVN -pl rule-eval-svc -am test`
Expected: PASS(EvalServiceImplTest 全绿,命令改型 + 派发改造闭合)

- [ ] **Step 4: Commit**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/async/InProcessAsyncCommandChannel.java \
        rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/EvalAutoConfiguration.java
git commit -m "feat(eval): channel 调用 finalDecision + 装配去 bindingIndex 依赖(D27 补齐)"
```

---

### Task 9: decision 写 API(tenant 级 CRUD,含 actions)

**Files:**
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/service/DecisionService.java`
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/DecisionServiceImpl.java`
- Create: `rule-api/src/main/java/com/sstlfsj/rule/web/admin/DecisionController.java`
- Create: `rule-api/src/main/java/com/sstlfsj/rule/web/admin/dto/DecisionCreateRequest.java` 等 DTO
- Test: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/service/DecisionServiceImplTest.java`

> 照 `SceneActionBindingServiceImpl` 套路:参数校验、落库、发 `OperationAuditedEvent` 审计。decision 是 tenant 级,无需 `SceneChangedEvent`。

- [ ] **Step 1: 写失败测试(service 层 CRUD)**

Create `DecisionServiceImplTest.java`:

```java
package com.sstlfsj.rule.config.internal.service;

import com.sstlfsj.rule.config.internal.domain.*;
import com.sstlfsj.rule.config.internal.repository.DecisionDefinitionMapper;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionAction;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DecisionServiceImplTest {

    private final DecisionDefinitionMapper mapper = mock(DecisionDefinitionMapper.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final DecisionServiceImpl svc = new DecisionServiceImpl(mapper, publisher);

    @Test
    void create_persistsAndAudits() {
        var actions = List.of(new DecisionAction("a1", "SEND_ALERT", 0, Map.of()));
        svc.create(9001L, "REJECT", "拒绝", 10, "拒绝类", actions, "actor");

        ArgumentCaptor<DecisionDefinition> cap = ArgumentCaptor.forClass(DecisionDefinition.class);
        verify(mapper).insert(cap.capture());
        assertThat(cap.getValue().getCode()).isEqualTo("REJECT");
        assertThat(cap.getValue().getActions()).hasSize(1);
        assertThat(cap.getValue().getStatus()).isEqualTo(DecisionStatus.ACTIVE);
        verify(publisher).publishEvent(any(OperationAuditedEvent.class));
    }

    @Test
    void create_rejectsDuplicateCode() {
        when(mapper.findByCode(9001L, "REJECT")).thenReturn(new DecisionDefinition());
        assertThatThrownBy(() -> svc.create(9001L, "REJECT", "拒绝", 10, null, List.of(), "actor"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已存在");
    }
}
```

- [ ] **Step 2: 运行验证失败**

Run: `$MVN -pl rule-config-svc -am test -Dtest=DecisionServiceImplTest`
Expected: FAIL(`DecisionService`/`DecisionServiceImpl` 不存在)

- [ ] **Step 3: 写 DecisionService 接口**

Create `DecisionService.java`:

```java
package com.sstlfsj.rule.config.api.service;

import com.sstlfsj.rule.config.internal.domain.DecisionDefinition;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionAction;
import java.util.List;

/** decision_definition tenant 级写服务(D26/D27:Decision 是 tenant 级一等实体)。 */
public interface DecisionService {

    /** 新建 decision;code 在 tenant 内唯一,重复抛 IllegalArgumentException。 */
    Long create(Long tenantId, String code, String name, Integer priority,
                String description, List<DecisionAction> actions, String actorId);

    /** 更新 decision 的 name/priority/description/actions(按 tenantId+code 定位)。 */
    void update(Long tenantId, String code, String name, Integer priority,
                String description, List<DecisionAction> actions, String actorId);

    /** 停用 decision(status → DISABLED)。 */
    void disable(Long tenantId, String code, String actorId);

    /** 列出 tenant 下所有 decision。 */
    List<DecisionDefinition> list(Long tenantId);
}
```

- [ ] **Step 4: 写 DecisionServiceImpl**

Create `DecisionServiceImpl.java`(照 `SceneActionBindingServiceImpl` 的审计事件写法):

```java
package com.sstlfsj.rule.config.internal.service;

import com.sstlfsj.rule.config.api.service.DecisionService;
import com.sstlfsj.rule.config.internal.domain.DecisionDefinition;
import com.sstlfsj.rule.config.internal.domain.DecisionStatus;
import com.sstlfsj.rule.config.internal.event.OperationAuditedEvent;
import com.sstlfsj.rule.config.internal.repository.DecisionDefinitionMapper;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionAction;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** {@link DecisionService} 实现:tenant 级 CRUD + 审计事件(无 SceneChangedEvent,decision 非 scene 级)。 */
@Service
@RequiredArgsConstructor
public class DecisionServiceImpl implements DecisionService {

    private final DecisionDefinitionMapper mapper;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public Long create(Long tenantId, String code, String name, Integer priority,
                       String description, List<DecisionAction> actions, String actorId) {
        if (mapper.findByCode(tenantId, code) != null) {
            throw new IllegalArgumentException("decision 编码已存在: code=" + code);
        }
        DecisionDefinition d = new DecisionDefinition();
        d.setTenantId(tenantId);
        d.setCode(code);
        d.setName(name);
        d.setPriority(priority);
        d.setDescription(description);
        d.setActions(actions != null ? actions : List.of());
        d.setStatus(DecisionStatus.ACTIVE);
        d.setCreatedBy(actorId);
        d.setCreatedAt(LocalDateTime.now());
        mapper.insert(d);
        audit(tenantId, actorId, "CREATE", d.getId());
        return d.getId();
    }

    @Override
    @Transactional
    public void update(Long tenantId, String code, String name, Integer priority,
                       String description, List<DecisionAction> actions, String actorId) {
        DecisionDefinition d = requireDecision(tenantId, code);
        d.setName(name);
        d.setPriority(priority);
        d.setDescription(description);
        d.setActions(actions != null ? actions : List.of());
        d.setUpdatedBy(actorId);
        d.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(d);
        audit(tenantId, actorId, "UPDATE", d.getId());
    }

    @Override
    @Transactional
    public void disable(Long tenantId, String code, String actorId) {
        DecisionDefinition d = requireDecision(tenantId, code);
        d.setStatus(DecisionStatus.DISABLED);
        d.setUpdatedBy(actorId);
        d.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(d);
        audit(tenantId, actorId, "DISABLE", d.getId());
    }

    @Override
    public List<DecisionDefinition> list(Long tenantId) {
        return mapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DecisionDefinition>()
                .eq(DecisionDefinition::getTenantId, tenantId));
    }

    private DecisionDefinition requireDecision(Long tenantId, String code) {
        DecisionDefinition d = mapper.findByCode(tenantId, code);
        if (d == null) throw new IllegalArgumentException("decision 不存在: code=" + code);
        return d;
    }

    private void audit(Long tenantId, String actorId, String action, Long id) {
        eventPublisher.publishEvent(new OperationAuditedEvent(
                tenantId, actorId, "USER", action, "decision_definition", String.valueOf(id),
                null, null, LocalDateTime.now()));
    }
}
```

> `OperationAuditedEvent` 构造参数顺序照 `PublishService:212-216` 的实参核对(tenantId, actorId, actorType, action, targetType, targetId, before, after, time);before/after 传 null。

- [ ] **Step 5: 运行验证通过**

Run: `$MVN -pl rule-config-svc -am test -Dtest=DecisionServiceImplTest`
Expected: PASS

- [ ] **Step 6: 写 Controller + DTO**

Create `DecisionController.java`(照 `SceneActionBindingController` 的 `/admin/v1` 路径 + `X-Actor-Id` header 写法):

```java
package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.config.api.service.DecisionService;
import com.sstlfsj.rule.config.internal.domain.DecisionDefinition;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionAction;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** decision tenant 级写 API(D26/D27)。 */
@RestController
@RequestMapping("/admin/v1/decisions")
public class DecisionController {

    private final DecisionService decisionService;

    public DecisionController(DecisionService decisionService) {
        this.decisionService = decisionService;
    }

    /** 新建 decision。 */
    @PostMapping
    public Long create(@RequestHeader("X-Tenant-Id") Long tenantId,
                       @RequestHeader("X-Actor-Id") String actorId,
                       @RequestBody DecisionRequest req) {
        return decisionService.create(tenantId, req.code(), req.name(), req.priority(),
                req.description(), req.actions(), actorId);
    }

    /** 更新 decision。 */
    @PutMapping("/{code}")
    public void update(@RequestHeader("X-Tenant-Id") Long tenantId,
                       @RequestHeader("X-Actor-Id") String actorId,
                       @PathVariable String code, @RequestBody DecisionRequest req) {
        decisionService.update(tenantId, code, req.name(), req.priority(),
                req.description(), req.actions(), actorId);
    }

    /** 停用 decision。 */
    @PostMapping("/{code}/disable")
    public void disable(@RequestHeader("X-Tenant-Id") Long tenantId,
                        @RequestHeader("X-Actor-Id") String actorId,
                        @PathVariable String code) {
        decisionService.disable(tenantId, code, actorId);
    }

    /** 列出 tenant 下所有 decision。 */
    @GetMapping
    public List<DecisionDefinition> list(@RequestHeader("X-Tenant-Id") Long tenantId) {
        return decisionService.list(tenantId);
    }

    /** decision 写请求体。 */
    public record DecisionRequest(String code, String name, Integer priority,
                                  String description, List<DecisionAction> actions) {}
}
```

> header 名(`X-Tenant-Id`/`X-Actor-Id`)与既有 controller 对齐——执行时核对 `SceneActionBindingController` 实际 header 命名,保持一致。

- [ ] **Step 7: 运行 api 模块测试**

Run: `$MVN -pl rule-api -am test`
Expected: PASS(controller 装配成功,无编译错误)

- [ ] **Step 8: Commit**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/service/DecisionService.java \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/DecisionServiceImpl.java \
        rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/service/DecisionServiceImplTest.java \
        rule-api/src/main/java/com/sstlfsj/rule/web/admin/DecisionController.java
git commit -m "feat(config,api): decision tenant 级写 API(CRUD + actions + 审计)(D27 补齐)"
```

---

### Task 10: Phase 2 收尾——回填 demo decision + 全量验证

**Files:**
- 数据操作(通过 API,无代码)
- Verify: 全量 `$MVN clean test`

- [ ] **Step 1: 全量 clean test 兜底**

Run: `$MVN clean test`
Expected: PASS(所有模块绿;DecisionBinding/Decision 扩字段后 ~40 处旧构造点经委托构造仍编译;派发改造闭合)

- [ ] **Step 2: 回填 demo decision(开 DECISION_CODE_NOT_FOUND 后存量规则可重发)**

通过 `POST /admin/v1/decisions` 建 REJECT/REVIEW/PASS(REJECT 挂 SEND_ALERT action),示例:

```bash
curl -X POST http://localhost:8080/admin/v1/decisions \
  -H 'X-Tenant-Id: 9001' -H 'X-Actor-Id: admin' -H 'Content-Type: application/json' \
  -d '{"code":"REJECT","name":"拒绝","priority":1,"description":"高风险拒绝",
       "actions":[{"actionId":"alert-1","actionType":"SEND_ALERT","sortOrder":0,"params":{}}]}'
```

> 压测 lt-rule 直接删;demo rule 867 引用 REJECT,建好 decision 后重发 demo 规则验证 finalDecision.name 非空、action 派发。

- [ ] **Step 3: Commit(若有 demo 数据脚本/文档更新)**

```bash
git add docs/examples/risk-control/high-risk-login/
git commit -m "docs(examples): demo 回填 REJECT/REVIEW/PASS decision 含 action(D27 补齐)"
```

---

# Phase 3 — 砍 scene_action_binding 整表(决策三)

> 前置:Phase 2 已让派发不再依赖 `SceneActionBindingIndex`。本阶段整表删除 + 删三模块相关类/测试 + D50 写 API 作废 + 文档收敛。

### Task 11: Flyway drop table scene_action_binding

**Files:**
- Create: `rule-config-svc/src/main/resources/db/migration/V1_22__drop_scene_action_binding.sql`

- [ ] **Step 1: 写迁移**

```sql
-- V1_22__drop_scene_action_binding.sql
-- 砍 scene_action_binding 整表:action 触发源唯一=decision、与 scene 无关(配置闭环 B 轮决策三)
DROP TABLE IF EXISTS scene_action_binding;
```

- [ ] **Step 2: Commit**

```bash
git add rule-config-svc/src/main/resources/db/migration/V1_22__drop_scene_action_binding.sql
git commit -m "feat(config): drop scene_action_binding 表(B 轮决策三)"
```

---

### Task 12: 删 eval 侧 scene_action_binding 相关类 + 装配 + 测试

**Files:**
- Delete: `rule-eval-svc/.../internal/action/SceneActionBindingIndex.java`
- Delete: `rule-eval-svc/.../internal/repository/SceneActionBindingReadMapper.java`
- Delete: `rule-eval-svc/.../internal/domain/SceneActionBindingRow.java`
- Delete: `rule-eval-svc/.../internal/domain/SceneActionBindingFullRow.java`
- Delete: 对应 4 个测试类(`SceneActionBindingIndexTest` / `SceneActionBindingReadMapperTest` / `SceneActionBindingRowTest`,以及任何引用)
- Modify: `rule-eval-svc/.../EvalAutoConfiguration.java`(删 `SceneActionBindingIndex` @Bean + import)
- Modify: 若 `SceneChangedEvent` 监听里有 binding 索引失效分支 → 删该分支

- [ ] **Step 1: 删类与测试**

```bash
git rm rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/action/SceneActionBindingIndex.java \
       rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/repository/SceneActionBindingReadMapper.java \
       rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/domain/SceneActionBindingRow.java \
       rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/domain/SceneActionBindingFullRow.java \
       rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/action/SceneActionBindingIndexTest.java \
       rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/repository/SceneActionBindingReadMapperTest.java \
       rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/domain/SceneActionBindingRowTest.java
```

- [ ] **Step 2: 清 EvalAutoConfiguration 与 SceneChangedEvent 残留引用**

`grep -rn "SceneActionBinding" rule-eval-svc/src/main` 找出所有残留(@Bean 定义、import、索引热更监听里的失效调用),逐处删除。SceneChangedEvent 监听中若调用 `bindingIndex.invalidate(...)` 之类,删该行(action 已不走 binding,无需失效)。

- [ ] **Step 3: 编译 + 测试**

Run: `$MVN -pl rule-eval-svc -am test`
Expected: PASS(无 `SceneActionBinding` 符号残留,eval 全绿)

- [ ] **Step 4: Commit**

```bash
git add -A rule-eval-svc/
git commit -m "refactor(eval): 删 scene_action_binding 索引/实体/读口(B 轮决策三)"
```

---

### Task 13: 删 config 侧 scene_action_binding 相关类 + 测试(D50 写服务作废)

**Files:**
- Delete: `rule-config-svc/.../internal/domain/SceneActionBindingDef.java`
- Delete: `rule-config-svc/.../internal/repository/SceneActionBindingMapper.java`
- Delete: `rule-config-svc/.../api/service/SceneActionBindingService.java`
- Delete: `rule-config-svc/.../internal/service/SceneActionBindingServiceImpl.java`
- Delete: 对应测试(`SceneActionBindingDefTest` / `SceneActionBindingMapperTest` / `SceneActionBindingServiceImplTest`)

- [ ] **Step 1: 删类与测试**

```bash
git rm rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/SceneActionBindingDef.java \
       rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/repository/SceneActionBindingMapper.java \
       rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/service/SceneActionBindingService.java \
       rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/SceneActionBindingServiceImpl.java \
       rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/domain/SceneActionBindingDefTest.java \
       rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/repository/SceneActionBindingMapperTest.java \
       rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/service/SceneActionBindingServiceImplTest.java
```

- [ ] **Step 2: 清残留引用(bundle import/export 等)**

`grep -rn "SceneActionBinding" rule-config-svc/src/main` 找残留。若 `RuleImportService`/`RuleExportService`/`RuleBundle` 含 scene_action_binding 字段或处理 → 一并删除该字段与处理逻辑(bundle 不再含 action binding)。

- [ ] **Step 3: 编译 + 测试**

Run: `$MVN -pl rule-config-svc -am test`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add -A rule-config-svc/
git commit -m "refactor(config): 删 scene_action_binding 实体/Mapper/写服务,D50 写 API 作废(B 轮决策三)"
```

---

### Task 14: 删 api 侧 scene_action_binding Controller/Convert + 测试

**Files:**
- Delete: `rule-api/.../web/admin/SceneActionBindingController.java`
- Delete: `rule-api/.../web/admin/convert/SceneActionBindingConvert.java`
- Delete: 对应测试(`SceneActionBindingControllerTest` / `SceneActionBindingConvertTest`)

- [ ] **Step 1: 删类与测试**

```bash
git rm rule-api/src/main/java/com/sstlfsj/rule/web/admin/SceneActionBindingController.java \
       rule-api/src/main/java/com/sstlfsj/rule/web/admin/convert/SceneActionBindingConvert.java \
       rule-api/src/test/java/com/sstlfsj/rule/web/admin/SceneActionBindingControllerTest.java \
       rule-api/src/test/java/com/sstlfsj/rule/web/admin/convert/SceneActionBindingConvertTest.java
```

- [ ] **Step 2: 清残留引用**

`grep -rn "SceneActionBinding" rule-api/src` 找残留 DTO/import,删干净。

- [ ] **Step 3: 编译 + 测试**

Run: `$MVN -pl rule-api -am test`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add -A rule-api/
git commit -m "refactor(api): 删 scene_action_binding Controller/Convert(B 轮决策三)"
```

---

### Task 15: 文档收敛 + 全量 clean test

**Files:**
- Modify: `docs/00-decisions.md`(追加决策条目)
- Modify: `docs/01-concepts.md` / `docs/04-extension.md` / `docs/05-storage.md` / `docs/06-frontend.md`

- [ ] **Step 1: 跑 doc-consistency-review**

改文档前先用 `doc-consistency-review` skill 扫 00/01/04/05/06 当前自洽基线。

- [ ] **Step 2: 00-decisions 追加决策(不改 D27 历史条目)**

追加一条:D27 本轮实装(decision.actions 接进派发);触发源单一性(否 C 两层并存);scene_action_binding 整表砍除、D50 作废;scene_metric_binding 砍除;方案甲快照内嵌 actions(守 D6,改 decision 需重发)。

- [ ] **Step 3: 其余章节收敛**

- `01-concepts`:§3.7 Action 归属确认挂 decision、§3.19 Decision 字段表加 actions、删 scene_action_binding/scene_metric_binding 概念。
- `04-extension`:ActionHandler actionType 合法性 = 运行期 NO_HANDLER skip(去"注册到 Scene 白名单"描述)。
- `05-storage`:decision_definition.actions 启用说明;删 scene_action_binding、scene_metric_binding 表 DDL。
- `06-frontend`:配规则/decision 时 actionType 下拉不再按 scene 白名单过滤(改全局 handler 列表或自由填)。

- [ ] **Step 4: 全量 clean test 兜底**

Run: `$MVN clean test`
Expected: PASS(全模块绿,无 scene_action_binding/scene_metric_binding 符号残留)

- [ ] **Step 5: rule-engine-reviewer 审代码↔文档对齐**

显式调用 `rule-engine-reviewer` agent,审 B 轮 docs 与 rule-* 代码对齐(D27 实装、两张 binding 表砍除)。

- [ ] **Step 6: Commit**

```bash
git add docs/
git commit -m "docs: B 轮配置闭环收敛(D27 实装 + 砍两张 binding 表 + D50 作废)"
```

---

## Self-Review 检查记录

- **Spec 覆盖**:决策一(补齐 D27)= Task 2-10;决策二(砍 metric binding)= Task 1;决策三(砍 scene_action_binding)= Task 11-14;文档落点 = Task 15。幂等键本轮不碰(spec 已定 B,留 best-effort 轮)——计划无相关任务,符合。
- **spec 修正**:`decision_definition` 无 category 列,发布期只冻 name+actions(priority 已在 binding),Task 4/5 已据此实现(category 仍由 tree executor 在 eval 期填)。执行时同步把 spec §2 "name/priority/category" 改为 "name/actions"。
- **类型一致**:`DecisionBinding(decisionCode, name, priority, actions)` 与 `Decision(code, name, priority, fromRuleVersionId, category, actions)` 字段贯穿 Task 2/3/4/5/7 一致;`dispatch(..., Decision finalDecision)` 签名贯穿 Task 6/7/8 一致。
- **兼容性**:DecisionBinding/Decision 委托构造保 ~40 处旧 (code, priority) 构造点不破——Task 2/3 已含 compat ctor 测试。
- **占位扫描**:测试辅助方法(`alwaysTrueAst`/`stubDraftWithBindings`/`RecordingPublisher` 等)标注为"照本测试类既有写法",非 TODO 占位——执行 agent 按既有同类用例对齐即可。
