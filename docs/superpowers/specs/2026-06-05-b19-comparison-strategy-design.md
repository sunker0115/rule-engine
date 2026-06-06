# B19 类型化比较策略 设计文档

> 来源：backlog B19（trae R3）。设计日期 2026-06-05。

## 目标

让条件比较按 metric 声明的 `dataType` 走类型正确的策略，停止当前"先试 number 再退 string"的运行时猜测；
顺带把散落在各算子里重复的强转/比较逻辑收口到可独立测试的策略类。

这是一次**行为变更**（修正潜在判错），不是纯重构。

## 背景：与 backlog 描述的偏差

backlog B19 写的是"替换 `ConditionEvaluator` 内部的 instanceof if-else"。实际代码里**没有那个 if-else**：

- 算子分派早已是"每算子一个 `ConditionEvaluator` 类"的策略表（`KernelEvaluators.defaults()`，17 个算子按算子码 key）。
- 真正的现状是：`MetricValue` 带了 `dataType` 字段**但无人读取**；各算子各自"猜类型"：
  - 数值算子（GT/GTE/LT/LTE/BETWEEN）走 `AbstractNumericEvaluator.toNumber()` → `Double.compare`；
  - `EQ`/`NEQ` 先试数值比较、失败回退 `String.equals`；
  - `IN`/`NOT_IN` 走 `String.valueOf` 成员判定；`CONTAINS`/`NOT_CONTAINS` 是 LIST（Collection）成员判定；字符串算子走 `String.valueOf`；
  - `DATE_BEFORE`/`DATE_AFTER` 解析成 `Instant`。

潜在 bug：STRING 类型指标值 `"0100"` 与阈值 `"100"` 会被当数值判定相等（手机号/邮编/单号场景错误）；
所有数值比较塌缩到 `double`，money / 大整数 ID 有精度丢失；Boolean 无专门处理。

## 关键约束（探索得到的事实）

1. `dataType` 存储枚举 5 种（`05-storage.md:113` / `01-concepts.md:467`）：`LONG / DOUBLE / STRING / BOOLEAN / LIST`。
   注意：`03-rule-expression.md §3.4` 另引用了 `DATE / DATETIME` 类型 + `$now/$today` 占位符 + 时区，
   但存储枚举与 `DateBeforeEvaluator` 实现都没有——既有的文档/实现背离，B19 不修，另记 backlog（B20）。
2. 运行时 `MetricValue.dataType()` **不可信**：`EvalContextAssembler:51` 把所有 PROVIDED（SDK 主路径）指标的
   dataType 硬编码成 `"UNKNOWN"`，各处取值词汇也不统一。→ 不能依赖运行时 dataType 路由。
3. `metric_definition` 按 `tenantId + metricCode` 检索（无 sceneId）。
4. `PublishService` 反序列化 AST 但**存的是 draft 原始 JSON**（`:168`），且未注入 `MetricDefinitionMapper`。
5. `AstSerializer` 走 Jackson record 组件序列化；给 `ConditionNode` 加 `dataType` 组件自动进出 JSON。
   新项目无存量 AST，发布写入的 JSON 一律含 dataType；唯一 null 来源是 DSL（见 §6）。

## 设计决策

| # | 决策 | 取舍 |
|---|------|------|
| 1 | dataType **发布期冻结进 `ConditionNode`**，求值期读 `node.dataType()` | 不依赖不可信的运行时 dataType；与引擎"发布期冻结"一贯做法一致 |
| 2 | 发布期**校验算子×dataType 兼容性**（仅 dataType 已知时） | 把错误挡在发布期；查不到的 metric 跳过校验，不破坏未注册 metric 的规则 |
| 3 | 薄策略 + 算子委托（方案 1） | 类型正确性落在真正有 bug 的数值/字符串比较上；不为不存在的 DATE 类型和"纯委托"仪式买单 |
| 4 | Numeric 策略内核用 **BigDecimal**，不加 DECIMAL 枚举 | money / 大整数精确比较，零 schema 改动 |
| 5 | dataType 为 null（仅 DSL）/无法识别/`LIST` → **Default 策略**（按运行时值 Java 类型推断） | 新项目无存量、不做兼容；DSL 未声明类型时按值推断；LIST 算子（CONTAINS/NOT_CONTAINS）自洽不经策略 |

---

## §1 AST schema 变更（rule-kernel）

`ConditionNode` 加第 6 个 record 组件 `String dataType`，并加一个 5 参构造器委托到 6 参（`dataType=null`），
作为**未声明类型的构造入口**（DSL 等不关心类型的调用方）。

```java
public record ConditionNode(
        String conditionType,
        String metricCode,
        String displayLabel,
        Map<String, Object> params,
        Double weight,
        String dataType          // 发布期冻结：LONG/DOUBLE/STRING/BOOLEAN/LIST；DSL/未解析为 null
) implements AstNode {
    public ConditionNode {
        params = Map.copyOf(params);
    }
    /** 未声明类型的构造入口（DSL 等），dataType=null（走 Default 策略）。 */
    public ConditionNode(String conditionType, String metricCode, String displayLabel,
                         Map<String, Object> params, Double weight) {
        this(conditionType, metricCode, displayLabel, params, weight, null);
    }
}
```

Jackson 用 canonical 6 参构造器进出 JSON；DSL 构造的节点 dataType 为 null。

## §2 发布期冻结 + 兼容性校验（rule-config-svc）

- `PublishService` 注入 `MetricDefinitionMapper`，按 `tenantId + metricCode IN(metricDeps)` 一次查出
  `metricCode → dataType` 映射。
- 新建 `AstDataTypeResolver`（参考 `MetricDependencyCollector` 的遍历范式）：
  - 递归遍历 AST，给每个 `ConditionNode` 用映射填 `dataType`（查不到的 metric → 保持 null）；
  - 同时按 §5 矩阵**校验算子×dataType 兼容性**（dataType 已知且不在允许集 → 抛 `IllegalArgumentException`）；
  - record 不可变，返回重建后的新 AST。
- 发布流程改动：
  - `:104` 反序列化后调用 `AstDataTypeResolver.resolve(ast, dataTypeMap)` 得到 `resolvedAst`；
  - `:168` `newRv.setConditionAst(astSerializer.toJson(resolvedAst))`（改存重序列化后的 AST，而非 draft 原始 JSON）；
  - `:210` snapshot 传 `resolvedAst`，eval-svc 热更立即拿到 dataType。

## §3 策略层（rule-kernel）

新增包 `com.sstlfsj.rule.kernel.internal.condition.strategy`：

```java
public interface ComparisonStrategy {
    /** actual 与 operand 的序关系：负/零/正；无序类型（Boolean/List）抛 UnsupportedComparisonException。 */
    int compare(Object actual, Object operand);
    /** actual 与 operand 是否相等。 */
    boolean equals(Object actual, Object operand);
}
```

策略实现：

- `NumericComparisonStrategy`（LONG + DOUBLE 共用）
  - 强转 `toBigDecimal(Object)`：`Number → new BigDecimal(n.toString())`、`BigDecimal → 原样`、
    `String → new BigDecimal(s)`；非法/null → 比较返回 false。
  - Double 的 NaN/Infinity 无法转 BigDecimal → 该比较 false。
  - `compare` 用 `BigDecimal.compareTo`（忽略 scale，`100` == `100.00`）；
  - `equals` 用 `compareTo == 0`（**不用** `BigDecimal.equals`，后者 scale 敏感）。
- `StringComparisonStrategy`：`compare` 字典序（`String.compareTo`），`equals` 走 `String.equals`；两侧 `String.valueOf`。
- `BooleanComparisonStrategy`：`equals` 比布尔值（`Boolean.parseBoolean`/`Boolean` 直取）；`compare` 抛 `UnsupportedComparisonException`。
- `DefaultComparisonStrategy`：dataType 未声明时（DSL）按 `actual` 运行时 Java 类型推断——`Number`/`BigDecimal` → 数值（BigDecimal）比较，`Boolean` → 布尔比较，其余 → 字符串比较。**前向默认，非复刻旧行为**。

LIST 不建专用策略：`CONTAINS`/`NOT_CONTAINS` 自带 Collection 成员逻辑（自洽，类比字符串算子），不经比较策略；
`forType(LIST)` 防御性归 Default。

工厂：

```java
public final class ComparisonStrategyFactory {
    public static ComparisonStrategy forType(String dataType) { ... }
}
```

`LONG`/`DOUBLE` → Numeric，`STRING` → String，`BOOLEAN` → Boolean，
`null`(DSL)/`LIST`/无法识别 → Default。返回缓存单例（策略无状态），零分配。

## §4 算子改造（rule-kernel）

**10 个算子改为委托策略**（求值期 `ComparisonStrategyFactory.forType(node.dataType())` 取策略）：

| 算子 | 委托方式 |
|------|---------|
| GT / GTE / LT / LTE | `strategy.compare(actual, threshold)` 取符号 |
| EQ / NEQ | `strategy.equals(actual, operand)` |
| BETWEEN / NOT_BETWEEN | `compare(actual,min)>=0 && compare(actual,max)<=0` |
| IN / NOT_IN | `values.anyMatch(v -> strategy.equals(actual, v))` |

`AbstractNumericEvaluator` 改造成"解析策略 + 模板方法 `accept(int cmp)`"的基类（GT/GTE/LT/LTE 继承）。

> 框架预留：`evaluate(node, ctx)` 入口是操作数"解析段"的位置——B19 的标量类型无符号可解析，此段为恒等，算子直接进策略；时间框架（B20）在此段用 `ctx` 解析 `$now`/`$today`/时区。`ComparisonStrategy` 因此保持纯 `(Object, Object)`，**不带 `EvalContext`**，B20 不破坏本接口。

**7 个算子保持原样**：`CONTAINS`/`NOT_CONTAINS`（LIST 成员判定，自洽）、
`STARTS_WITH`/`ENDS_WITH`/`MATCHES`（STRING 语义）、`DATE_BEFORE`/`DATE_AFTER`（自带 Instant 解析）。

## §5 兼容性矩阵（发布期校验 + 文档）

**权威来源：`03-rule-expression.md §3.1–3.4`。** `§3.5`（D20 §3 闭合校验）已规定"发布时引擎静态校验 metric 数据类型
与操作符是否匹配……类型不匹配在发布期拒绝"——**B19 正是实现这条早已设计、未落地的闸门**，矩阵不另发明，逐字对齐：

| 算子 | 允许的 dataType |
|------|----------------|
| EQ / NEQ | LONG, DOUBLE, STRING, BOOLEAN |
| GT / GTE / LT / LTE / BETWEEN / NOT_BETWEEN | LONG, DOUBLE |
| IN / NOT_IN | LONG, STRING |
| CONTAINS / NOT_CONTAINS | LIST |
| STARTS_WITH / ENDS_WITH / MATCHES | STRING |
| DATE_BEFORE / DATE_AFTER | （B19 不校验，见下） |

dataType 已知且不在允许集 → 发布抛 `IllegalArgumentException`；dataType 未知（查不到/null）→ 跳过该节点校验。

**DATE_* 排除**：`§3.4` 规定其作用于 `DATE/DATETIME`，但该类型不在存储枚举内（既有背离）。
B19 不校验 DATE_*、不动 `DateBefore/DateAfter` 评估器；DATE/DATETIME 类型补全 + `$now/$today` + 时区是独立 backlog 项（B20）。

## §6 未声明 dataType 的处理

新项目无存量数据，**不做老数据/老 JSON 兼容**。发布路径下每个 ConditionNode 都被冻结 dataType；
唯一 dataType 为 null 的来源是 **rule-sdk DSL**（作者不声明类型）。

`node.dataType() == null` → `DefaultComparisonStrategy`：按 `actual` 运行时 Java 类型推断
（`Number`/`BigDecimal` → 数值 BigDecimal、`Boolean` → 布尔、其余 → 字符串）。
这是"未声明类型时按值推断"的前向默认——比旧的"先试 number 再退 string"更准（如 String `"0100"` 不会被当数值）。

## §7 测试

- 策略单测：
  - Numeric：BigDecimal 精度（`50000.00` vs `50000`、`0.1+0.2`、超 2^53 大整数 ID）、NaN/Infinity → false；
  - String：`"0100"` ≠ `"100"`、字典序；
  - Boolean：`true`/`"true"` 相等、compare 抛异常；
  - Default：按运行时 Java 类型推断（Number→数值、Boolean→布尔、String→字符串，含 `"0100"`≠`"100"`）。
- 算子路由测试：10 个改造算子在各 dataType 下走对策略；`dataType=null` 走 Default 按值推断。
- `AstDataTypeResolver`：冻结 dataType 正确、兼容性校验拒绝非法组合、查不到的 metric 跳过。
- 序列化：AST JSON `dataType` round-trip；缺 `dataType` 字段的 JSON 反序列化为 null（Jackson 健壮性）。
- `PublishService`：发布冻结 dataType 进 `conditionAst`、非法算子×dataType 组合发布失败。

## 不做（Out of scope）

- 不加 DECIMAL dataType（BigDecimal 作为内部内核已覆盖）。
- 不动 DATE_*：`DATE/DATETIME` 类型补全 + `$now/$today` 占位符 + 时区/ISO-offset 解析 + 发布校验，是独立 backlog 项 B20（设计见 03-rule-expression §3.4，未实装）。
- 不改运行时 `EvalContextAssembler` 的 `"UNKNOWN"`（dataType 改走发布期冻结，运行时 dataType 不再用于路由）。
- 不动 DATE_*/字符串算子的现有解析逻辑。
- 不碰 SDK DSL 的 dataType 声明（DSL 路径 dataType=null 走 Default 按值推断，可后续单独增强）。

## 影响文档

落地后需同步：`01-concepts.md`（ConditionNode 加 dataType 字段说明）、
`05-storage.md`（conditionAst 内 dataType 冻结说明）、`08-evolution.md`（B19 标记已实装）、
`00-decisions.md`（追加决策条目）、`03-rule-expression.md §3.5`（标注发布期算子×dataType 校验已实装；矩阵仍为 §3.1–3.4 权威表）。
