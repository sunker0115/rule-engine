# DB 空字段审计：根因 + 使用者价值 + 冗余分析

> 日期：2026-06-09。范围：排查「库里很多表/列为空」的真实原因,逐项归到四类根因,并从使用者/消费者角度评「该不该有值、有没有、冗不冗余」。**本文档为只读分析,未改任何代码/schema。** 需改代码的项标注「→ superpowers」。

## 0. 必读前提：当前数据是压测 seed,严重偏样

库里数据基本只来自压测播种器 `LoadTestSeeder`(租户 9001):
- 规则全是 **AST_BOOLEAN**、单条件 `demo.score GTE 0`、`demo.score` 由请求 `providedMetrics` 传入、决策恒 `PASS`、命中恒 HIT。
- 无 SCORECARD / DECISION_TREE / DECISION_TABLE 规则;无 SQL_AGGREGATE 取数;无 action 绑定;末轮 `trace.enabled=false`。

**所以「列为空」必须区分四类根因,不能一锅烩:**
- **(a) 代码漏填**：schema/entity 有,写库代码没 set —— 真 bug。
- **(b) 场景没跑到**：该路径压测没触发 —— 数据偏,非代码问题。
- **(c) 设计为可选/保留**：本就 nullable 或显式预留 —— 正常。
- **(d) 冗余/死结构**：schema 里有,全链路无人写、运行时不读 —— 该决断(删或扶正)。

## 1. 有数据的表 —— 逐列空值实测

### evaluation_session（实测 746,894 行,全部 status=HIT）
| 列 | 非空率 | 根因 | 使用者是否需要 | 结论 |
|---|---|---|---|---|
| final_decision / hit_decisions / finished_at | 100% | — | 是 | 正常 |
| `eval_duration_ms` | **0%** | **(a) 漏填** | **是**(性能/SLO/排障必看) | **真 bug:`setEvalDurationMs` 全仓只在测试里调过,主链路从不 set;`finishedAt` 有了但 duration 没算** → superpowers |
| blocked_by | 0% | (b)+(c) | 仅 BLOCKED 时 | 正常(seed 无 pre-gate 拦截) |
| error_code | 0% | (b)+(c) | 仅 ERROR 时 | 正常(seed 无错误) |
| score | 0% | (b)+(c) | 仅 SCORECARD | 正常(无评分卡规则) |
| category | 0% | (b)+(c) | 仅 DECISION_TREE | 正常(无决策树规则) |
| `context_snapshot` | ~51%(历史残留) | **(a)/(d) 已查实** | 是(排障/重放) | **当前代码无人写此列**:唯一 `setContextSnapshot` 在 `DryRunPersister`,而它写的是**另一张表** `dry_run_session`(0 行);`AuditPersister` 写 `evaluation_session` 但从不 set 它。~51% 是某次重构前的旧数据残留。→ 见 §3.4 |

### node_trace（实测 8,136,590 行,均为单 ConditionNode）
| 列 | 非空率 | 根因 | 使用者是否需要 | 结论 |
|---|---|---|---|---|
| condition_type / metric_code / result | 100% | — | 是 | 正常(seed 是单条件,故恒有) |
| error_code | 0% | (b) | 仅出错时 | 正常(seed 无错误) |
| `actual_value` | **0%** | **(a) 设计未接线** | **是**(前端解释「实际值」核心) | 见 §3.1 —— 不是漏个 setter,是求值核心 `ConditionOutcome(status,errorCode)` **根本没带出实际值** → superpowers |
| `value_source` | **0%** | **(a) 设计未接线** | **是**(provided/fetched 影响可信度与排障) | 同上,executor 传 null,值其实在 `MetricValue` 里没透出 → superpowers |
| `params` | **0%** | **(a)+(d)** | **是**(前端「期望值/阈值」) | **双重孤儿**:`NodeTrace` model 压根没有 params 字段,`NodeTraceEntity.params` 列也从没被 set → superpowers |

## 2. 0 行的表 —— 场景没跑 vs 结构冗余

| 表 | 根因 | 结论 |
|---|---|---|
| action_execution | (b) | 正常:seed 未绑 action → 无派发。代码路径存在(Track A 曾写过,re-seed 清掉) |
| decision_definition | (b) | 正常:seeder 用内联 JSON binding 不建 decision;`RuleImportService` 有写它的代码 |
| dry_run_session / dry_run_node_trace | (b) | 正常:压测未走 dry-run;`DryRunPersister` 代码在 |
| job_definition / job_execution | (b) | 正常:调度未触发 |
| scene_action_binding | (b) | 正常:seed 未绑;有写 API + `SceneActionBindingIndex` |
| **rule_decision_binding** | **(d) 冗余** | **规范化表,但 decisionBindings 实际存 `rule_version.decision_bindings`(JSON);运行时与 `MetadataServiceImpl` 都读 JSON,这张表全链路无人写** |
| **scene_metric_binding** | **(d) 冗余** | **同上:metric 依赖存 `rule_version.metric_dependencies`(JSON);这张规范化表无人写** |

## 3. 三个真正要决断的问题（都需 superpowers 改代码）

### 3.1 trace 的 actual_value / value_source / params 从未落地（设计已考虑,接线缺失）
**根因链**:`ConditionOutcome` 是 `(status, errorCode)` 两字段 record —— 叶子求值算出了「满足与否」却**丢弃了实际值与来源**;各 executor 建 `NodeTrace` 时只能传 null;`flattenToList` 写 null;列恒空。`params` 更彻底:`NodeTrace` model 连字段都没有。

**这正是「设计时考虑了(列+注释都在),写代码时少加了」**,但缺口在求值核心,不是漏个 setter。

**使用者视角**:这三样恰是前端「规则解释 UI」最值钱的内容——「实际 17 / 要求 ≥18 / 来自 provided」。当前前端拿不到。

**架构正解(非补丁)**:在求值源头补全——`ConditionOutcome` 扩成携带 `resolvedValue + valueSource`(求值器返回它本就算出的数据),executor 顺势写入 NodeTrace;`params/期望值`作为新字段统一加。**反面(打补丁)= 在 executor 里为了 trace 重新解析一遍值** —— 不可接受。理由充分:列与注释证明设计意图早已存在,本次是「补全既定契约」,非新增范围。
→ 与「面向前端的 trace」设计(本次会话已 brainstorm,暂定最小覆盖、增强后延)是同一件事,应合并走 superpowers。

### 3.2 eval_duration_ms 从未写入（纯漏填）
`finishedAt` 写了,`startedAt` 有,但没人算 `finishedAt - startedAt` 塞进 `eval_duration_ms`(主链路无 `setEvalDurationMs`)。使用者视角:性能/SLO/排障都要它。修法简单(persister 里算差值),但是代码改动 → superpowers。

### 3.4 evaluation_session.context_snapshot 当前无人写（重构遗留）
查实:`setContextSnapshot` 全主链路只有 `DryRunPersister` 一处,而它持久化的是 `DryRunSession`(→ `dry_run_session` 表,0 行),**不是** `evaluation_session`;写 `evaluation_session` 的 `AuditPersister` 从不 set 此列。故 `evaluation_session.context_snapshot` 当前代码路径**根本没有写入方**,库里 ~51% 非空是某次「把 context_snapshot 挪到 dry-run 专属」重构之前的旧数据残留。
**要决断(superpowers)**:列注释写着「排障 / dry-run 重放用」,暗示常规 session 也该有。二选一——
- (a) **回填**:若常规 session 也要上下文快照排障 → `AuditPersister` 补写(当前是丢失的回归);
- (d) **删列**:若快照只服务 dry-run(已落 `dry_run_session`)→ 删 `evaluation_session.context_snapshot` 并改注释,消除「看着该有其实没人写」的误导。

### 3.3 规范化 binding 表 vs JSON-blob 的双轨冗余（结构债）
`rule_decision_binding` / `scene_metric_binding` 是规范化关系表,但真实数据存在 `rule_version` 的 `decision_bindings` / `metric_dependencies` JSON 里,运行时与元数据查询都走 JSON,这两张表**从不写**。这是「结构冗余」:同一份数据有规范化 schema + JSON 两套表示,只用 JSON。

**要决断(二选一,都需 superpowers)**:
- (i) **扶正**:若将来配置管理/查询要按 binding 维度检索,则让写路径落规范化表、JSON 由其派生 —— 工作量大;
- (ii) **删除**:若 JSON 已是事实单一真相源,删掉这两张空表(及其 entity/mapper),消除「看着像设计了其实没用」的债。
greenfield 无数据包袱,倾向 (ii) 删,除非有明确的按-binding 检索需求。**这正是你说的「设计不能像打补丁」的反面教材——双轨并存却只用一轨,要给个明确理由收敛成一轨。**

## 4. 正常的空（无需动,记录备查）
- `compiled_predicate_ref`(rule_version):注释明确「v1 留空,v1.5 预编译启用」——**显式保留位**,(c) 正常。
- `rule_decision_binding.score_range_min/max`:注释「仅 SCORECARD,v1 留 null」——(c),但该表整体冗余(见 3.3)。
- 各表 `updated_by/updated_at/description/published_*`:未更新/无描述时为空,(c) 正常。

## 5. 行动清单（明天 review 后逐项走 superpowers）
| # | 事项 | 类别 | 处理方式 |
|---|---|---|---|
| 1 | trace 补 actual_value / value_source / params(含 `ConditionOutcome` 扩值 + NodeTrace 字段)——并入「面向前端的评估 trace」设计 | (a) 接线缺失 | superpowers(与 tree/table trace 合并设计) |
| 2 | eval_duration_ms 计算并写入 | (a) 漏填 | superpowers(小改) |
| 3 | rule_decision_binding / scene_metric_binding 冗余收敛(删 or 扶正) | (d) 结构债 | superpowers(需先定方向) |
| 4 | context_snapshot ~51% 成因核实 + 常规 session 是否需快照 | 待查 | 先 10 分钟代码追,再定是否 superpowers |
| 5 | tree/table trace 覆盖(本会话已 brainstorm,spec 待写) | 功能缺口 | superpowers(与 #1 合并为「面向前端 trace」一个 spec) |

> 关键结论:**绝大多数「空」是压测场景单一造成的假象(b/c),不是病**。真正要动的只有四件:trace 三字段接线缺失(#1)、eval_duration 漏填(#2)、规范化 binding 表冗余(#3)、context_snapshot 待查(#4);#1 与 tree/table trace(#5)是同一件「面向前端的 trace」,建议合并成一个 superpowers 设计一次做对,避免打补丁。
