# D42 `DECISION_TREE` / `DECISION_TABLE` evaluator

> **Goal：** 实现 `Rule.kind=DECISION_TREE` 和 `DECISION_TABLE` 两种新执行形态，新增对应 AST 节点和 Executor，复用现有 `RuleVersionExecutor` SPI 和 `EvalEngine`，`EvalResult` 补 `category` / `decision` 字段。

---

## 影响范围

| 模块 | 文件 | 变更类型 |
|------|------|---------|
| `rule-kernel` | `api/model/ast/IfNode.java` | 新建 |
| `rule-kernel` | `api/model/ast/DecisionLeafNode.java` | 新建 |
| `rule-kernel` | `api/model/EvalResult.java` | 修改：补 `category` / `decision` 字段 |
| `rule-kernel` | `internal/evaluator/DecisionTreeExecutor.java` | 新建 |
| `rule-kernel` | `internal/evaluator/DecisionTableExecutor.java` | 新建 |
| `rule-kernel` | `internal/codec/AstJsonCodec.java` | 修改：注册 IfNode / DecisionLeafNode |
| `rule-eval-svc` | `internal/EvalAutoConfiguration.java` | 修改：注册新 Executor |
| `rule-config-svc` | `internal/service/PublishService.java` | 修改：发布期 kind 校验 |
| `rule-kernel` 测试 | `DecisionTreeExecutorTest.java` | 新建 |
| `rule-kernel` 测试 | `DecisionTableExecutorTest.java` | 新建 |

---

## AST 节点设计

### `IfNode`（DECISION_TREE 分支节点）

```java
// rule-kernel api/model/ast/IfNode.java
public record IfNode(
        AstNode condition,    // 条件：任意 AstNode（通常是 ConditionNode 或 AndNode）
        AstNode thenBranch,   // 条件为 true 时走此子树
        AstNode elseBranch    // 条件为 false 时走此子树（可为 null 表示未命中）
) implements AstNode {}
```

### `DecisionLeafNode`（叶子节点，携带决策结果）

```java
// rule-kernel api/model/ast/DecisionLeafNode.java
public record DecisionLeafNode(
        String decisionCode,  // 命中时返回的 Decision code
        String category       // DECISION_TREE 用：分类标签（可与 decisionCode 相同）
) implements AstNode {}
```

### DECISION_TABLE JSON schema（存在 `conditionAst` 列的 JSON 字符串内）

```json
{
  "type": "DecisionTable",
  "columns": [
    {"metricCode": "amount",  "operator": "GT"},
    {"metricCode": "country", "operator": "IN"}
  ],
  "rows": [
    {"conditions": [1000, ["CN","HK"]], "decisionCode": "BLOCK"},
    {"conditions": [500,  ["US"]],      "decisionCode": "REVIEW"},
    {"conditions": [null, null],        "decisionCode": "PASS"}
  ]
}
```

`null` 表示该列通配（任意值均满足）。行顺序即优先级（FIRST_HIT 语义）。

---

## `EvalResult` 字段扩展

```java
// 现有字段不变，新增：
String category();   // DECISION_TREE 命中叶子节点时填充（nullable）
String decision();   // DECISION_TABLE 命中行时填充（nullable）
```

`satisfied()` 语义不变：命中任意 Decision 即为 true。

---

## Executor 实现

### `DecisionTreeExecutor`

递归遍历 `IfNode` 树：
```
evaluate(IfNode node, ctx):
  if evaluate(node.condition, ctx):
    return evaluate(node.thenBranch, ctx)
  else if node.elseBranch != null:
    return evaluate(node.elseBranch, ctx)
  else:
    return miss
evaluate(DecisionLeafNode leaf, ctx):
  return hit(leaf.decisionCode, leaf.category)
```

### `DecisionTableExecutor`

反序列化 JSON → 按行顺序匹配 → 第一条所有列满足的行胜出：
```
for row in table.rows:
  if allColumnsMatch(row, ctx):
    return hit(row.decisionCode)
return miss
```

列匹配委托给现有 `ConditionEvaluator`（用 `ConditionNode` 包装后复用 `InterpretedExecutor` 内部逻辑）。

---

## 实现步骤

### Step 1：新建 `IfNode` / `DecisionLeafNode`（rule-kernel）

同步：`AstJsonCodec` 注册 Mixin；补 AST 节点序列化 / 反序列化测试。

### Step 2：`EvalResult` 补 `category` / `decision`

`EvalResult` 是 record，补两个 nullable 字段；已有构造路径 `satisfiedResult()` / `missResult()` 补默认 null。

### Step 3：`DecisionTreeExecutor`（rule-kernel）

新建 `internal/evaluator/DecisionTreeExecutor.java`，实现 `RuleVersionExecutor`。

测试：`DecisionTreeExecutorTest` — 覆盖：单层 if/then、嵌套 if/else、末尾无 else（miss）

### Step 4：`DecisionTableExecutor`（rule-kernel）

新建 `internal/evaluator/DecisionTableExecutor.java`，实现 `RuleVersionExecutor`。

测试：`DecisionTableExecutorTest` — 覆盖：第一行命中、中间行命中、通配列（null）、全不命中（miss）

### Step 5：`EvalAutoConfiguration` 注册新 Executor

```java
Map.of(
    "AST_BOOLEAN",    astBooleanExecutor,
    "SCORECARD",      scorecardExecutor,
    "DECISION_TREE",  new DecisionTreeExecutor(evaluators),
    "DECISION_TABLE", new DecisionTableExecutor(evaluators)
)
```

### Step 6：`PublishService` 发布期 kind 校验

允许 `kind` 值：`AST_BOOLEAN` / `SCORECARD` / `DECISION_TREE` / `DECISION_TABLE`。

### Step 7：运行测试

```bash
$MVN -pl rule-kernel,rule-eval-svc,rule-config-svc -am test
```

### Step 8：更新文档

`10-api-contract.md §七`（创建规则端点）：补 DECISION_TREE / DECISION_TABLE 的 `conditionAst` schema 示例。
`08-evolution.md §2.1`：DECISION_TREE / DECISION_TABLE 状态改为"已实装"。

### Step 9：commit

```bash
git commit -m "feat(kernel): D42 DECISION_TREE / DECISION_TABLE evaluator"
```
