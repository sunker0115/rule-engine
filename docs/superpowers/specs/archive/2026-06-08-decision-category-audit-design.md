# 决策 category 审计 + 引擎决策来源修根 设计

> Status: 设计已批准（2026-06-08），待写实现计划。把决策树/决策表「executor 自选的决策」在引擎聚合时被最高优先级 binding 覆盖的 bug 修掉（修根），并让每条命中决策的分类标签 `category` 不再丢失、落进 `evaluation_session` 审计。

## 1. 动机

### 1.1 根因 bug：引擎聚合覆盖了 tree/table 的真实决策

- `DecisionTreeExecutor.hit()`（`rule-kernel/.../internal/evaluator/DecisionTreeExecutor.java:88-95`）按命中叶子 `leaf.decisionCode` **正确选出**决策（filter 出匹配 binding），并把 `category=leaf.category()` 放在 `EvalResult` 上。
- 但 `EvalEngine.evaluateAllCandidates`（`rule-kernel/.../internal/engine/EvalEngine.java:153-156`）**丢弃 executor 的 `r.hitDecisions()`/`r.finalDecision()`，改从 `snap.decisionBindings().max(priority)` 重建** Decision —— 取该规则**优先级最高的 binding**，而非叶子实际命中的那条。
- 后果：决策树 `bindings=[BLOCK(30), REVIEW(20), PASS(10)]`，事件实际落 PASS 叶子，审计/结果却记成 **BLOCK**。决策被静默篡改。
- 对 BOOLEAN 规则该行为是对的（布尔规则不自选决策、通常单 binding）；只有 tree/table 这类「executor 自选了决策」的被错误覆盖。
- 同时 `category` 在这一步被一并丢弃（`EvalEngine.java` 聚合后 `new EvalResult(..., aggregatedScore, null, null)`，第 8 位 category 恒置 null）。

### 1.2 FIRST_HIT 的对偶问题

`evaluateFirstHit` 反过来——它**用** executor 的 `r.hitDecisions()`（决策正确），但对 BOOLEAN 规则 `r.hitDecisions()` 为空、`r.finalDecision()` 为 null → `winner=null`。即 FIRST_HIT 下布尔规则命中却拿不到决策。两个策略对 boolean/tree 的处理正好相反，都各有缺口。

### 1.3 category 审计缺失

`evaluation_session` 无 category 列，`hit_decisions` 仅存决策码扁平数组 `["REVIEW"]`。多条决策树各判不同分类（中危/大额）时，分类标签全部丢失 —— 风控可解释性（为什么判这个档）与按档聚合统计（今天多少笔高危）都做不到。

## 2. 已批准的关键决策

- **范围 = 修根（A）**：引擎聚合统一用「executor 自选了决策就用它，没自选才回退 binding」，修复决策被覆盖 + FIRST_HIT 布尔缺口，并让 category 同源流转。
- **表示法 = 全做（c）**：明细保真（`hit_decisions` 升级对象数组）+ 主分类单列（`evaluation_session.category`）。
- **机制 = 方案 1**：`Decision` 自带 `category` 字段；引擎判别器以 `r.hitDecisions()` 是否非空区分「executor 自选」vs「未自选」，不按 kind 写分支。

## 3. 设计

### 3.1 核心模型：`Decision` 加 `category`

`rule-kernel/.../api/model/Decision.java`：

```java
public record Decision(String code, String name, int priority, Long fromRuleVersionId, String category) {
    /** 无分类（boolean/scorecard 等）的便捷构造，category=null。 */
    public Decision(String code, String name, int priority, Long fromRuleVersionId) {
        this(code, name, priority, fromRuleVersionId, null);
    }
}
```

便捷 4 参构造器保证所有现有 `new Decision(code,name,priority,ruleVersionId)` 调用点零改动（category 默认 null）。

### 3.2 Executor 把 category 焊到 Decision

`DecisionTreeExecutor.hit()`：把 `leaf.category()` 直接放进 Decision（不再只挂 EvalResult）：

```java
.map(b -> new Decision(b.decisionCode(), "", b.priority(), snapshot.ruleVersionId(), leaf.category()))
.orElseGet(() -> new Decision(leaf.decisionCode(), "", 0, snapshot.ruleVersionId(), leaf.category()));
```

decision-table 无 category（其 `EvalResult.category` 本就 null），Decision 的 category 自然为 null，无需改 `DecisionTableExecutor`（它走 4 参构造）。

### 3.3 引擎修根：共享判别器 `resolveRuleDecisions`

`EvalEngine` 新增私有方法，`evaluateAllCandidates` 与 `evaluateFirstHit` 共用：

```java
/** 一条命中规则贡献的决策：executor 自选了（tree/table，hitDecisions 非空）就用它（带 category）；
 *  否则（boolean/scorecard）回退按最高优先级 binding 赋决策（category=null）。 */
private List<Decision> resolveRuleDecisions(RuleVersionSnapshot snap, EvalResult r) {
    if (!r.hitDecisions().isEmpty()) return r.hitDecisions();
    return snap.decisionBindings().stream()
            .max(Comparator.comparingInt(RuleVersionSnapshot.DecisionBinding::priority)
                    .thenComparing(RuleVersionSnapshot.DecisionBinding::decisionCode))
            .map(b -> List.<Decision>of(
                    new Decision(b.decisionCode(), "", b.priority(), snap.ruleVersionId())))
            .orElse(List.of());
}
```

- `evaluateAllCandidates`：每条命中规则 `hitDecisions.addAll(resolveRuleDecisions(snap, r))`，替掉现有无条件 binding 重建块。
- `evaluateFirstHit`：`List<Decision> rd = resolveRuleDecisions(snap, r); Decision winner = rd.stream().max(DECISION_PRECEDENCE).orElse(null);` 命中返回 `new EvalResult(true, winner, winner==null?List.of():List.of(winner), r.nodeTrace(), r.errorCode(), List.of(), r.score(), winner==null?null:winner.category(), null)`。
- 两策略聚合后 `finalDecision = hitDecisions.max(DECISION_PRECEDENCE)`（自带胜出 category）；聚合 `EvalResult.category = finalDecision != null ? finalDecision.category() : null`（现在恒 null → 修复，API 响应也能见主分类）。
- 单条规则的 `resolveRuleDecisions` 返回 0 或 1 个决策（tree/table 单叶子/单行，boolean/scorecard 单 binding），`hitRuleCount` 语义不变。

### 3.4 审计持久化

`AuditPersister`：
- 两个构造器注入全局 `ObjectMapper` Bean（不 new，遵循项目约定）。
- `toSession`：
  - `hit_decisions` 改用 ObjectMapper 序列化对象数组（替掉手工 `Collectors.joining`）：每元素 `{"code":..., "category":..., "ruleVersionId":...}`（`ruleVersionId = Decision.fromRuleVersionId()`，回答「哪条规则」）。
  - `s.setCategory(r.finalDecision() != null ? r.finalDecision().category() : null)`。
- `EvaluationSession` 加字段 `private String category;`（Lombok @Setter 已生成 setter）。

### 3.5 迁移

`rule-config-svc/src/main/resources/db/migration/V1_10__add_session_category.sql`：

```sql
-- D42 DECISION_TREE 主分类审计：evaluation_session 增 category 列（finalDecision 同源，单列可聚合；明细在 hit_decisions）。
-- greenfield 无生产数据，空表直接 ADD。
ALTER TABLE evaluation_session
    ADD COLUMN category VARCHAR(64) NULL COMMENT 'DECISION_TREE 主分类（finalDecision 同源）；其他 kind NULL' AFTER score;
```

## 4. 错误处理 / 边界

- 决策树取数失败（`evaluateIf` cond.isError）→ 整规则 miss + errorCode，不命中叶子 → 不产生 Decision，category 不参与。沿用现状。
- `resolveRuleDecisions` 对无 binding 的非 tree/table 规则返回空 list（理论不该发生，防御性）。
- `hit_decisions` 空时序列化为 `[]`（保持现状语义）。
- category 始终可空：boolean/scorecard/decision-table 命中均为 null，`session.category` 为 null，合法。

## 5. 测试 + 验收

- **Decision**：category 字段 + 4 参便捷构造器（category=null）。
- **DecisionTreeExecutor**：命中叶子的 Decision 带 `leaf.category()`。
- **EvalEngine（核心回归）**：
  - 覆盖 bug 回归：ALL_HITS，决策树 `bindings=[BLOCK30/REVIEW20/PASS10]` 命中 PASS 叶子 → `finalDecision.code()=="PASS"`（非 BLOCK）、`finalDecision.category()` = PASS 档。
  - 多树多 category：两条决策树各判中危/大额 → `hitDecisions` 两条各带 category，`finalDecision.category()` = 胜出者档。
  - FIRST_HIT 布尔回归：布尔规则命中 → winner 来自 binding（非 null）、category=null。
  - boolean/scorecard：binding 回退不变、category=null（既有用例不破）。
- **AuditPersister**：`hit_decisions` 为对象数组含 category；`session.category` = finalDecision.category。
- **EvaluationSession**：category 字段 round-trip。
- **全量**：rule-kernel + rule-eval-svc 模块测试全绿；迁移在干净库可应用。

## 6. 非目标

- 不改 `EvalResult.category`/`decision` 的对外响应字段定义（仅修聚合时的填值）。
- 不动 decision-table 的 `decision` 字段语义（与 finalDecision.code 冗余，不新增列）。
- 不把 category 引入 node_trace（条件级 trace 不变）。
- 不改 PULL/PUSH/dry-run 对外契约。
