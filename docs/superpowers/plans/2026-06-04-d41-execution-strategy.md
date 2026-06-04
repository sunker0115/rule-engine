# D41 `Scene.executionStrategy` 扩展 — ALL_HITS / FIRST_HIT

> **Goal：** 在现有 `HIGHEST_PRIORITY` 基础上新增 `ALL_HITS`（全部命中）和 `FIRST_HIT`（短路）两种策略，`EvalEngine` 按策略分支，`EvalResult` API 不变。

---

## 影响范围

| 模块 | 文件 | 变更类型 |
|------|------|---------|
| `rule-kernel` | `api/model/SceneExecutionStrategy.java`（已有枚举） | 修改：加 `ALL_HITS` / `FIRST_HIT` |
| `rule-kernel` | `internal/engine/EvalEngine.java` | 修改：按 strategy 分支 |
| `rule-kernel` | `internal/index/SceneRuleIndex.java` | 修改：携带 strategy 字段 |
| `rule-kernel` | `api/model/RuleVersionSnapshot.java` | 确认：snapshot 是否需要携带 strategy（scene 级字段） |
| `rule-eval-svc` | `internal/service/EvalServiceImpl.java` | 修改：查 scene strategy 传入 EvalEngine |
| `rule-config-svc` | `internal/service/SceneService.java` | 修改：新枚举值校验 |
| `rule-kernel` 测试 | `EvalEngineStrategyTest.java` | 新建：三种策略分支覆盖 |

---

## 策略语义

| strategy | 评估行为 | `hitDecisions()` | `finalDecision()` |
|---|---|---|---|
| `HIGHEST_PRIORITY` | 全量评估，取最高优先级 | 所有命中 | 最高优先级 |
| `ALL_HITS` | 全量评估，收集所有命中 | 所有命中 | 最高优先级（保持有意义） |
| `FIRST_HIT` | 按 priority 倒序，命中即停 | 唯一命中 | 唯一命中 |

`FIRST_HIT` 下规则必须按优先级倒序排列后再遍历（`SceneRuleIndex` 已按 priority 排序），第一条满足条件的规则命中后立即返回，其余规则跳过（节省评估开销）。

---

## `EvalEngine` 伪代码

```java
// FIRST_HIT 分支
if (strategy == FIRST_HIT) {
    for (RuleVersionSnapshot rule : sortedByPriorityDesc) {
        EvalResult r = executor.execute(rule, ctx);
        if (r.satisfied()) return buildResult(List.of(r.hitDecisions().get(0)));
    }
    return buildMissResult();
}

// HIGHEST_PRIORITY / ALL_HITS 分支
List<Decision> allHits = sortedByPriorityDesc.stream()
        .map(rule -> executor.execute(rule, ctx))
        .filter(EvalResult::satisfied)
        .flatMap(r -> r.hitDecisions().stream())
        .toList();

if (allHits.isEmpty()) return buildMissResult();

Decision final = allHits.stream().max(Comparator.comparingInt(Decision::priority)).get();
return buildResult(allHits, final);
```

---

## 实现步骤

### Step 1：`SceneExecutionStrategy` 枚举加值

```java
public enum SceneExecutionStrategy {
    HIGHEST_PRIORITY,
    ALL_HITS,
    FIRST_HIT
}
```

同步测试：`SceneExecutionStrategyTest` 验证枚举值存在。

### Step 2：`SceneRuleIndex` 携带 strategy

`SceneRuleIndex` 现在只存规则列表；需要补 `Map<String, SceneExecutionStrategy> sceneStrategies`，key 为 `sceneCode`，从 `Scene` 实体加载。

SDK 本地模式默认策略：`HIGHEST_PRIORITY`（`localSnapshot` / `ruleFile` 不传 strategy 时的默认值）。

### Step 3：`EvalEngine.evaluate()` 分支

按 `SceneRuleIndex.getStrategy(sceneCode)` 取策略，三分支实现。

同步：`EvalEngineStrategyTest.java` — 覆盖：
- `HIGHEST_PRIORITY`：多规则命中取最高优先级
- `ALL_HITS`：多规则全部收集
- `FIRST_HIT`：第一条命中即停，第二条跳过

### Step 4：`EvalServiceImpl` 传递 strategy

`SceneRuleIndex` 加载时从 `Scene` 表读 `executionStrategy`，写入 index。

### Step 5：`SceneService` 新枚举值校验

`Scene.executionStrategy` 字段已是 VARCHAR，新值 `ALL_HITS`/`FIRST_HIT` 需在 service 校验层通过（更新白名单）。

### Step 6：运行测试

```bash
$MVN -pl rule-kernel,rule-eval-svc,rule-config-svc -am test
```

### Step 7：commit

```bash
git commit -m "feat(kernel): D41 executionStrategy 扩展——ALL_HITS / FIRST_HIT 短路策略"
```
