# 决策矩阵录入 demo + 决策表 BETWEEN 修复 — 设计

> 状态：已确认设计，待落地。来源：与天网决策引擎（skyhackvip/risk_engine）对标后，验证"二维决策矩阵是录入便利、引擎模型不变"这一结论。

## 1. 背景与动机

天网决策引擎有独立的 `matrix`（交叉决策表）节点：X 轴分箱 × Y 轴分箱 → 输出格。本项目的 `DECISION_TABLE`（`DecisionTableNode(columns, rows)`，FIRST_HIT，N 列）在**表达力上已覆盖且更通用**——二维矩阵 = 2 列 + m×n 行的特例。

差异只在**录入体感**：稠密二维网格用矩阵填表比逐行枚举省。本设计做一个**丢弃式可视化 demo** 证明这一点（矩阵视图 → 实时展开成现有 `DecisionTableNode` JSON），而非新增引擎能力。

落地前发现一个**真实潜伏 bug**：决策表的 `BETWEEN`/`NOT_BETWEEN` 列运行时永不命中。demo 的矩阵分箱依赖 BETWEEN，故一并修复。

## 2. Part 1 — 决策表 BETWEEN 修复（真修复，TDD）

### 2.1 bug 现状

- 前端 `DecisionTableEditor` 的 `TABLE_OPERATORS` 含 `BETWEEN`/`NOT_BETWEEN`，用户可选。
- `DecisionTableExecutor.buildParams`（`rule-kernel/.../internal/evaluator/DecisionTableExecutor.java`）只有两分支：`IN/NOT_IN → {"values": condValue}`、`default → {"threshold": condValue}`。BETWEEN 落进 default，得到 `{"threshold": [...]}`。
- `BetweenEvaluator` / `NotBetweenEvaluator` 读 `params.get("min")` / `params.get("max")`，缺任一即 `return false`。
- 结果：决策表 BETWEEN 列**永远 false、永不命中**。决策表测试只用 stub 的 GT/EQ，未覆盖，故一直未暴露。

### 2.2 修复

- `buildParams` 增 `BETWEEN`/`NOT_BETWEEN` 分支：行条件值约定为二元 `List [lo, hi]` → 映射成 `{"min": lo, "max": hi}`（对齐 `ConditionParams.MIN/MAX`，即 `BetweenEvaluator` 期望）。
- 形状不符（非 2 元 List）时不强造 min/max（保持与现状一致的"不命中"，不抛异常打断整表）。
- **修复面单一**：`buildParams` 是决策表唯一 params 构建点——eval-svc 无孪生，`AstCompiler` 只编译 `AST_BOOLEAN`（§2.13），`DecisionTableExecutor` 是决策表唯一执行器。无并列孪生路径。

### 2.3 测试（先红后绿）

在 `DecisionTableExecutorTest` 增用例，用**真实** `BetweenEvaluator`（端到端验证 buildParams→evaluator 接线，而非 stub）：

- metric `amount=2000`，列 `BETWEEN`，行 `[1000,5000]` → HIT；行 `[0,1000]` → MISS。
- `NOT_BETWEEN` 对称一条。
- 修复前复现测试为红，修复后转绿。

命令：`$MVN -pl rule-kernel -am test`；一轮结束 `$MVN clean test` 全量兜底。

## 3. Part 2 — 决策矩阵录入 demo（丢弃式单 HTML）

### 3.1 产出

- 单个自包含 `docs/examples/decision-matrix-mockup.html`：原生 JS + 内联 CSS，无依赖、无构建，双击即开，可随时删。
- 不引 React、不进 `frontend` 工程、不接后端、不调真实 API。

### 3.2 布局与交互

- **左·矩阵视图**：
  - X 轴 / Y 轴各：特征下拉（预置 `age` / `amount` 等示例）+ 分箱列表（每箱 `{label, lo, hi}`，可增删改）。
  - 网格：行=Y 分箱、列=X 分箱，每格一个决策下拉（`PASS` / `REVIEW` / `BLOCK`，文案可改）。
- **右·展开为 DECISION_TABLE（实时）**：
  - `columns = [yCol(BETWEEN), xCol(BETWEEN)]`（2 列）。
  - 对每个 `(yi, xi)`：`row = { conditions: [[y.lo,y.hi], [x.lo,x.hi]], decisionCode: cells[yi][xi] }`，共 m×n 行。
  - 底注："引擎模型 0 改动 / FIRST_HIT / m×n 行"。
- 改任意分箱 / 格子 → 右侧 JSON 实时刷新。

### 3.3 数据模型（镜像 kernel）

- `DecisionTableNode { columns: Column[], rows: Row[] }`
- `Column { metricCode, operator:"BETWEEN", dataType:null, valueRef:null }`
- `Row { conditions: [[lo,hi], [lo,hi]], decisionCode }`
- demo 内部矩阵态：`{ xAxis:{feature, buckets:[{label,lo,hi}]}, yAxis:{...}, cells[y][x] }`。

## 4. 范围与非目标

**做**：上述 demo + buildParams BETWEEN/NOT_BETWEEN 修复 + 对应 kernel 测试。

**不做（YAGNI）**：
- demo 不做后端 / 保存 / 真实 API / N 维 / 校验 / 多租户 / i18n。
- 不动真实 `DecisionTableEditor`（其行条件用单 `<Input>` 存字符串，BETWEEN/IN 无法正确录入——是**独立的前端缺口**，本设计只标记不修）。
- 不新增引擎 kind / matrix 节点（矩阵是录入糖，不是引擎能力）。

## 5. 验收

- Part 1：kernel 测试绿（含新增 BETWEEN/NOT_BETWEEN 用例）；`clean test` 全量通过。
- Part 2：手动开页面 → 改一个格子、加一个分箱 → 右侧 JSON 实时正确、行数=m×n、conditions 为 [lo,hi] 二元数组。UI mockup 无自动化测试（明示"未亲自跑自动化，靠手动验"）。
- 链路自洽：demo 产出的 JSON 形状与修复后的 kernel 决策表一致——即"矩阵展开的决策表引擎真能跑通"。
