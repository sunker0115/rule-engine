# 表达式编辑器智能辅助：现状与演进

**日期**：2026-07-20
**状态**：L1 变量补全已落地（2026-07-23，`expressionCompletions.ts` + ScriptEditor + ExpressionInput，零后端）；L2 类型诊断 已落地（2026-07-23，`ExpressionValidationService` + `POST /admin/v1/expressions/validate` + 前端 debounced lint）；L3 LSP/自然语言 仍待定

## 一、既有基础（已落地）

表达式智能不是零。以下设施在 EXPRESSION_SCRIPT（D66）落地时已经就位，构成了加编辑器智能的地基：

### 1.1 跨引擎统一的变量引用抽取（SPI 标准化）

`ExpressionEngine.compile(source)` → `CompiledExpression.referencedVariables()` 是 SPI 契约方法（`CompiledExpression.java:11`），六个引擎全部实现。编译后返回的引用变量集形如 `metrics.txn_cnt_1d` / `payload.amount`，已在**发布期依赖冻结**中实际使用（`MetricDependencyCollector` 扫它来拼 `metricDependencies`）。

**这意味着什么**：不用重新发明「表达式里用了哪几个变量」——每个引擎编译时已经算好了，且是可信的（发布期已经在吃，经过验证）。把它暴露给前端编辑器，直接变成补全候选项。

### 1.2 CEL 的 AST 级变量抽取（强类型引擎）

`CelReferencedVariables.from(ast)`（`rule-expression-cel/.../CelReferencedVariables.java`）遍历 dev.cel checked AST 的 `SELECT` 表达式节点，精确抽取 `metrics.x` / `payload.x` / `subject.x` 命名空间下的字段选择。不是正则猜，是语法树级别的精确结果。

### 1.3 CEL 的发布期类型检查（已有 typed validation）

`ExpressionEngine.typeCheck(source, ScriptTypeEnv)`（`ExpressionEngine.java:42`）——CEL 实现了，按被引用变量的声明类型（`metrics.x → LONG`、`payload.y → STRING`）构造 typed CEL 编译环境，捕获「string 字段参与数值比较」等类型不符。目前只在**发布期**跑——草稿提交时校验，不过即拒 400。

### 1.4 CEL 编译缓存（内容寻址，线程安全）

`CelExpressionEngine`（`:38`）内部 Caffeine 缓存，按源码内容哈希 key，同源脚本跨规则/版本共享编译产物。意味着「每次 keystroke 重新编译」的实际成本极低——同源码第二次命中的是缓存，零编译开销。

---

## 二、缺什么：对标 GoRules ZEN 的 intellisense

ZEN 的表达式内核（`core/expression/src/intellisense/`）在编译之外多做了四件事：

| ZEN 能力 | 对应文件 | 本项目有没有 |
|---|---|---|
| **补全**：按当前位置列出合法字段/操作符 | `completion.rs` | 没有。但可用变量列表已经有了（`referencedVariables`），只是没喂给编辑器 |
| **实时诊断**：keystroke 级语法/类型错误提示（红波浪线） | `diagnostic.rs` | 没有。编译期错误只在发布时报（草稿提交 400），不在编辑时实时给 |
| **类型推断**：从上下文推导表达式各子树的类型 | `type_provider.rs` | 没有。但 CEL 的 `typeCheck` 已经能按 typed env 做校验——只是缺一条「实时的」路径 |
| **自然语言**：自然语言片段转表达式 | `nl/` | 没有。且考虑到中文运营场景 + 准确度风险，暂不跟 |

**本质差距不是「引擎算不出来」，是「编辑期没用上」**——既有编译/类型检查基础设施已经能回答补全和建议，只是管了发布期的闸，没管编辑期的提示。

---

## 三、分级方案

### Level 1 —— 编辑器变量补全（只动前端，零后端改动，建议现在就做）

**做什么**：ScriptEditor（Monaco Editor / CodeMirror）增加 completion provider，按上下文自动补全 `metrics.xxx` / `payload.xxx` / `subject.xxx` 的可用字段名。

**数据来源**（全部已在前端可用，不需要新接口）：
- 当前 Scene 的 `inputManifest`（`GET /api/v1/rule/scenes/{sceneCode}/input-manifest`）列出了所有可用 payload 字段 + dataType
- 当前 tenant 的 metric 列表（`GET /admin/v1/metrics`）列出了所有已注册 metric 的 code + dataType
- 引擎生成环境变量（`now` / `subjectId` / `tenantId` 等）是固定列表

**实现**：
- Monaco：注册 `CompletionItemProvider`，在 `.` 后触发，按光标前的命名空间前缀（`metrics.` / `payload.` / `subject.`）过滤候选项
- 候选项带 dataType 提示（如 `metrics.txn_cnt_1d (LONG)`）
- 对非 CEL 引擎（Aviator/Groovy 等），同样适用——变量名是所有引擎共通的，补全是 editor 层功能

**成本**：前端 1 个 completion provider 文件，Monaco API 标准用法，后端零改动。0 新接口、0 新依赖。

### Level 2 —— CEL 实时类型诊断（一个轻量 API + 前端 debounced 调，中低成本）

**做什么**：ScriptEditor 里 CEL 表达式写错了类型（如 `metrics.age > "hello"`），在编辑时就出红波浪线，而不是等点「保存草稿」才被 `typeCheck` 打回来。

**后端**：新增 `POST /admin/v1/expressions/validate`，入参 `{source, lang, sceneCode, tenantId}`。
- 后端按 `sceneCode` 查出该 Scene 的 metric 列表 + payloadSchema，拼 `ScriptTypeEnv`
- 调 `ExpressionEngine.typeCheck(source, typeEnv)`，返回 `{valid: boolean, errors: [{line, column, message}]}`
- 对弱类型引擎（Aviator/JEXL 等），`typeCheck` 默认 no-op，返回 `{valid: true}`（不会误报）
- **复用已有的编译缓存**：CEL 的 `compile(source)` 走 Caffeine，keystroke 高频调 validate 的编译成本≈0

**前端**：Monaco `setDiagnosticsOptions` + debounced（300ms）调用 validate 端点，把返回的 error 映射为 editor markers（红波浪 + hover message）。

**成本**：
- 后端：rule-api 新增一个 controller 方法 + 一个请求/响应 record（约 30 行）；kernel 侧 `typeCheck` 已就位，零内核改动
- 前端：Monaco diagnostics 集成（约 50 行）
- 开销：debounced 调用 + Caffeine 缓存，编译压力可忽略

### Level 3 —— 完整语言服务器（LSP，不推荐现在做）

- 多引擎各自实现 LSP（completion/hover/signatureHelp/diagnostics/codeActions），投入产出比低
- CEL 有一个非官方的 LSP 实现可考虑复用，但引入新依赖 + 适配各引擎的 maintenance 成本高
- 等 Level 1+2 落地后，看运营实际使用中遇到多少表达式编写困难，再判断值不值得上 Level 3

---

## 四、与 DECISION_FLOW 的关系

DECISION_FLOW 里的 `SwitchNode` 和 `TransformNode` 都持有表达式（`expression` 字段 + `ExpressionLang`），它们复用同一个 `ExpressionEngine` SPI。Level 1 的变量补全对这俩节点的表达式编辑器同样生效（同一个 completion provider，只是上下文换成「当前 flow 里 flow 图上游已有变量 + scene 全局可用变量」）。

Level 2 的类型诊断也同理：Switch/Transform 节点的表达式同样可以按 flow 上下文构造 `ScriptTypeEnv`（含上游 Transform 产出的变量），做到整个 flow 图内的跨节点类型连贯——**这已经碰到 zen 的 `entity_flow` 边界了**，是 Level 2 的最大价值点。但 flow 上下文变量推导本身需要 flow walker 支持（知道某节点上游的 Transform 声明的 outputKey + 类型），这可以作为 DECISION_FLOW P1（FlowExecutor）完成后的增量。

---

## 五、建议

- **立即做 Level 1**：零后端改动，利用已就位的 `referencedVariables` + metric/payload 列表数据，前端一个 completion provider 搞定。对运营写表达式的体验提升最大（不用记 metricCode，选就行）。
- **DECISION_FLOW P1 完成后做 Level 2 基础版**（仅 CEL、不含 flow 上下文）：利用已就位的 `typeCheck`，一个轻量 validate 端点，让 CEL 编辑有实时红波浪线。
- **Level 2 flow 上下文版 + Level 3** 留到有真实摩擦时再做。

Level 1 的成本低到接近零——本质上就是把已经在发布期用的数据，在编辑期也喂给编辑器。
