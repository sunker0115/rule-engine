# 契约常量集中 + 评估 trace 重构 记录（2026-06-09）

> 本轮工程记录。两件事:(1) 面向前端的评估 trace 功能;(2) 顺带把散落的「契约魔法字符串」集中成枚举/常量、消除裸构造。全程在 `develop`,commit 区间 `973dd81`..`c2d5c54`,全 reactor `clean test` BUILD SUCCESS,最终 opus 审查 Ready to merge。

## 1. 评估 trace 功能(详见 spec/plan)
- 设计:`docs/superpowers/specs/2026-06-09-eval-trace-frontend-design.md`
- 计划:`docs/superpowers/plans/2026-06-09-eval-trace-frontend.md`
- 4 增量:① `ConditionOutcome` 带出 resolvedValue/valueSource + Interpreted/Scorecard 填实 + `ScorecardRoot` 根节点;② tree/table 补全保真 NodeTrace(读 `TraceScope.COLLECT`,关闭零分配);③ NodeTrace 加 `expectedValue`(→ 复用 `node_trace.params` 列)+ `displayLabel`(→ 新增 `display_label` 列),主链 `TraceWriterDbImpl` + dry-run 链 `DryRunTraceWriterDbImpl` 两端 + Flyway `V1_12`;④ `eval_duration_ms` 用 `EvalContext.now` 起点 + 事件 `durationMs`(并修了 `started_at`/`finished_at` 都盖落库时刻的旧 bug)。
- 落库形态选 **A(自包含)**:label/expected/actual 随 trace 行落库,前端单行可渲染;kind 由 root nodeType 自描述(ScorecardRoot/IfNode/DecisionTableRow/其余)。A→B(运行时-only + 前端叠 AST)低成本可逆。

## 2. 契约常量集中(约定:**不得出现裸契约字符串**)

「契约字符串」= 既被代码引用、又落库/序列化/作 DB 枚举值的字符串。一律集中到 `rule-kernel/.../api/model/` 下单一真相源,**值与原字面量字节一致**(改值=静默契约破坏/数据腐蚀)。新增同类值时只在这一处加。

| 族 | 形态 | 位置 | 值域 | 备注 |
|---|---|---|---|---|
| 节点类型 | `enum NodeType` + `tag()` | api.model | AndNode/OrNode/NotNode/XorNode/IfNode/ConditionNode/DecisionLeafNode/DecisionTableRow/ScorecardRoot | 落 `node_trace.node_type`;前端按 root tag 判 kind |
| 错误码 | `final class EvalErrorCode`(String 常量) | api.model | METRIC_FETCH_FAIL/NO_EVALUATOR/CONDITION_EVAL_ERROR/各 AST_TYPE_MISMATCH… | 落 `node_trace.error_code`/`evaluation_session.error_code` |
| 取值来源 | `enum ValueSource` + `tag()` | api.model | PROVIDED/FETCHED | 落 `node_trace.value_source` ENUM |
| 规则种类 | `enum RuleKind` + `tag()` | api.model | AST_BOOLEAN/SCORECARD/DECISION_TREE/DECISION_TABLE/EXPRESSION_SCRIPT | == DB `rule_*.kind` ENUM;作 executor map key |
| 数据类型 | `enum DataType` + `tag()`/`fromTag()` | api.model | LONG/DOUBLE/DECIMAL/STRING/BOOLEAN/DATE/DATETIME/LIST/UNKNOWN | == DB `metric_definition.data_type`;`ComparisonStrategyFactory` 用 `fromTag` 枚举分派;`MetricEnums.DATA_TYPES` 从枚举派生**排除 UNKNOWN**(UNKNOWN 是运行时哨兵,非合法 metric 类型) |
| 取数源类型 | `final class SourceType`(String 常量) | api.model | ATTRIBUTE/SQL_AGGREGATE/EXTERNAL_HTTP/STREAM | **必须是 String 常量而非枚举**——用在 `@MetricSourceType(...)` 注解,注解值需编译期常量;`MetricEnums.SOURCE_TYPES` 从 `SourceType.ALL` 派生 |

**为什么 NodeType/RuleKind/ValueSource/DataType 是枚举、EvalErrorCode/SourceType 是常量类:** 枚举给类型安全 + 穷尽性(如 DataType 分派 switch 穷尽,加类型即编译报错);但**注解值**(`@MetricSourceType`)和 **switch case 标签**需要编译期 String 常量,枚举方法调用不能用,故 SourceType 用常量类。EvalErrorCode 同理偏常量。

## 3. 语义工厂(消除裸构造)
`new EvalResult(false, null, List.of(), …)` / `new NodeTrace("XxxNode", null,…,null)` 这类裸构造已清零,改语义工厂:
- `EvalResult.hit()/miss()/miss(trace)/error(code)/error(code, trace)`
- `NodeTrace.container(NodeType, result, children, rvId)` 及带 errorCode 重载;叶子 ConditionNode 仍走全参构造(带 expectedValue/displayLabel)
- `RowResult.matched()/notMatched()/error(code)`(DecisionTableExecutor 私有 record;组件 `matched`→`isMatch` 以让出工厂名)

## 4. 顺带修的预存破损
`rule-sdk` `RuleEngineClient` 与 `rule-benchmark` `EvalEngineBenchmark` 的 `EvalEngine(...)` 构造缺 trace-skip 那轮新增的 `boolean traceEnabled`——两模块不在 rule-app 依赖树,之前 reactor 构建没覆盖到,故长期编译破损未被发现。各补 1 行。**教训:改 kernel 公开签名后跑一次全 reactor `clean test`,别只 `-pl rule-app -am`。**

## 5. Backlog(本轮**有意不做**,等驱动)
- **DSL 算子 / conditionType**("GT"/"GTE"/"IN"…):散在几百处,自成一个项目,未收。
- **params-map key**("datasource"/"sql"/url 等):各 source-type handler 的 key 不一 + 嵌套用户 params 不确定,**不是固定契约**,有意不集中。
- **XorNode 作 tree 条件**:`DecisionTreeExecutor.evaluateCondition` 无 XorNode case → 落 `NO_EVALUATOR` ERROR。**预存限制**(非本轮引入),未修;要支持需补 case + 测试。
- **DB 空字段**(见 `docs/audits/2026-06-09-db-empty-fields-audit.md`):规范化 binding 表冗余(删/扶正)、`evaluation_session.context_snapshot` 重构后无人写(回填/删列)——各为独立决策。
