# 忠实重放(时间旅行式重现历史评估)— 设计

> 状态:已实现 · 日期:2026-06-12 · 关联决策:D70(见文末)
> 前置:D21(trace)、D23(`(tenant,event_id)` 幂等 + Replay 换 eventId)、D25(EvalContext 装配)、D30(providedMetrics)、D44(evalNow 单次注入)、D49(EventSource.REPLAY)、D56(规则版本不可变、SUPERSEDED 保留)、D59(规则身份 code+version)

## 1. 背景与目标

引擎已能**查看**历史执行(`evaluation_session` + `node_trace` + `context_snapshot`),也能 **dry-run 试算**(对任意版本跑一个事件,但用当前数据、重新取数)。但**不能忠实重现"当时那一刻"**:

- `evaluation_session` **不存 payload**(只存 eventId/scene/eventType/subjectId + metric 快照)。
- 没有"锁当时版本 + 用历史 metric 回灌 + 跳过取数"的重放路径——重放会用当前规则 + 当前取数。

**目标**:给定一个历史 `evaluation_session`,**忠实重跑**它——锁当时的规则版本、灌当时的 payload + metric + evalNow、**跳过重新取数**,产出与当时一致的 `EvalResult` + `nodeTrace`,回答"当时为何这样判"。

### 已对齐决策

| 分叉 | 选择 |
|---|---|
| 重放语义 | **忠实重现历史**(锁 `fromRuleVersion` 当时版本 + `context_snapshot` 历史 metric + 原始 payload + 历史 evalNow);非 what-if |
| payload 存储 | **`evaluation_session` 加 payload 列,默认存**(配 TTL/保留,见 §6) |

### 非目标

- 不做 what-if(历史数据 × 当前规则)——本次只做忠实重现;what-if 留后续(可复用本设计的快照回灌、只把"锁版本"换成"取当前版本")。
- 重放**只读、零副作用**(不落新 `evaluation_session`、不触发任何下游;D60 本就无 action)。
- 不重建被采样/关闭 trace 丢失的轨迹(见 §5 边界)。

## 2. 忠实重现需要冻结/回灌什么

一次评估的输出 = f(规则版本集, payload, metrics, evalNow, subject, pre-gate)。逐项看"当时值"从哪来:

| 输入 | 当时值来源 | 现状 | 本设计 |
|---|---|---|---|
| **规则版本集**(候选,非仅命中) | 当时倒排索引匹配出的候选快照 id | `evaluation_session` 只存 `candidateRuleCount`(数量),无 id 列表 | **新增 `candidate_rule_version_ids` 列**(JSON),回放按 id 精确加载 |
| **规则版本快照本身** | `rule_version`(不可变,SUPERSEDED 保留,D56) | ✅ 已有,`snapshotLoader.loadById` 可取历史版本 | 复用 |
| **payload** | 原始事件 | ❌ 不存 | **新增 `payload` 列**(JSON) |
| **metrics** | 评估那刻取数结果 | ✅ `context_snapshot.metrics`(`{code:rawValue}`) | 回灌,跳过 fetch |
| **evalNow** | 评估统一时刻 | ✅ `context_snapshot.evalNow` | 回灌为 `now` |
| **pre-gate(ROLLOUT)** | murmur3 分桶,无状态、确定性(D52) | 由 subjectId/experimentId 决定性重算 | 自然一致,无需冻结 |
| **subject** | SubjectLoader 加载的主体属性 | ❌ 不在 snapshot | §5 边界(重载当前 / 后续可扩 snapshot) |

核心结论:补两列(`payload`、`candidate_rule_version_ids`),metric/evalNow 复用现有 `context_snapshot`,规则版本复用不可变版本库——即可忠实重现绝大多数评估。

## 3. 架构

### 3.1 落库侧(评估时多冻结两列)

`AuditRecordedEvent` 已携带 `RuleEvent event`(含 payload)与候选数;评估时 `candidates` 列表的版本 id 也在手。落库链路(`AuditPersister`)在写 `evaluation_session` 时多写:

- `payload` = `event.payload()` 的 JSON(复用全局 ObjectMapper;失败写 null,best-effort)。
- `candidate_rule_version_ids` = 候选快照 `ruleVersionId` 列表的 JSON。

> `AuditRecordedEvent` 需补 `List<Long> candidateVersionIds`(评估时从 `candidates` 取),payload 已可从 `event` 取。

### 3.2 回放侧(新端点 + 新引擎入口)

**端点**:`POST /admin/v1/evaluation-sessions/{sessionId}/replay?tenantId=...` → `ApiResponse<ReplayResult>`(`ReplayResult` = `EvalResult` + 元信息:锁定的版本集、是否快照完整)。

**回放服务** `ReplayService.replay(tenantId, sessionId)`:
1. 加载 `evaluation_session`(租户校验);取 `payload`、`candidate_rule_version_ids`、`context_snapshot`。
2. 快照完整性校验:`context_snapshot` 或两新列缺失 → 抛 `REPLAY_NOT_REPRODUCIBLE`(见 §7),不猜测。
3. 按 `candidate_rule_version_ids` 经 `snapshotLoader.loadById` 加载**历史候选快照**(SUPERSEDED 也能取)。
4. 反序列化 `context_snapshot`:`metrics{code:rawValue}` → `Map<String,MetricValue>`(包成 `MetricValue(value, dataType?, PROVIDED)`),`evalNow` → `Instant`。
5. 重建 `RuleEvent`:`eventId`(原值,replay 只读不落库,无需换新)、scene/eventType/subjectId + `payload`,`source=REPLAY`。
6. 调**新引擎入口** `EvalEngine.evaluateReplay(event, candidates, frozenMetrics, evalNow)`:用冻结 metrics + evalNow **直接组装 EvalContext、跳过 `EvalContextAssembler` 取数**,按候选快照评估(强制收集 trace),返回 `EvalOutcome`。
7. 返回 `EvalResult` + `nodeTrace`;**不写任何表**。

**关键新增**:`EvalEngine` 一个回放入口,接受外部冻结的 `metrics` + `now`,绕过取数装配。这是与 dry-run 的本质区别——dry-run 重新取数,replay 回灌历史取数。

### 3.3 数据流

`POST .../replay` → `ReplayService` 读 session(payload/候选 id/snapshot)→ 加载历史候选快照 → 冻结 metrics+evalNow 组装 context → `evaluateReplay` → 返回 `EvalResult`+trace,零落库。

## 4. 与现有能力的关系

- **dry-run**:前瞻试算,当前数据、重新取数、可选任意版本(含草稿)。回答"这规则现在/对这数据会怎样"。
- **replay(本设计)**:回溯重现,历史数据、跳过取数、锁当时版本。回答"当时为何这样判"。
- 两者都**只读无副作用**;replay 复用 dry-run 的"返回 trace、不派发"结构,但取数与版本来源相反。

## 5. 忠实度边界(明确写清,不假装满分)

- **subject 不在 snapshot**:若规则条件引用 SubjectLoader 主体属性,replay 当前**重载当前 subject**(可能与当时不同)。多数条件走 metric+payload,影响有限;忠实重现 subject 需后续扩 `context_snapshot` 存 subject 快照(本次不做,列为后续)。
- **metric dataType/来源丢失**:`context_snapshot` 只存 `{code:rawValue}`,丢了 `dataType`/`valueSource`/`errorCode`。算子主要用 value,回放够用;但"当时取数失败(isError)"的细节重现不了。可选小增强:snapshot 多存 dataType(本次不做)。
- **trace 依赖**:候选集靠新列 `candidate_rule_version_ids` 精确还原,**不依赖 trace 完整性**(优于"从 node_trace 反推候选")。但 trace 关闭的历史 session 仍可 replay(重新算出 trace),只是当时没存的 trace 无法对比。
- **存量 session**:本特性上线**之前**的 session 没有 payload/候选 id 两列 → 不可 replay(`REPLAY_NOT_REPRODUCIBLE`),只能查看不能重放。
- **快照 best-effort 丢失**:`context_snapshot` 是 best-effort(可丢,序列化失败写 null)。null 的 session 不可 replay。

## 6. 存储与迁移

- **迁移(Flyway,下一版本号)**:`evaluation_session` 加两列——`payload`(JSON/TEXT,可空)、`candidate_rule_version_ids`(JSON/TEXT,可空)。均可空,兼容存量行。
- **保留/TTL**:payload 含业务输入,**PII 敏感**。保留期对齐 `evaluation_session` 既有 TTL 退休策略(与 trace/snapshot 同生命周期,D56 提及 TTL 退休);不单独长留。05-storage 同步该列与保留说明。
- **存储量**:payload 体积随业务,接受(用户选默认存);若后续压力大,再引 per-scene 开关(本次不做)。

## 7. 错误处理

| 情形 | 处理 |
|---|---|
| session 不存在 / 跨租户 | 404 / 403 |
| `context_snapshot` 为 null,或 `payload`/`candidate_rule_version_ids` 缺失(存量行/best-effort 丢失) | 400 `REPLAY_NOT_REPRODUCIBLE`,message 注明缺哪项;不猜测、不退化成"当前数据重放" |
| 某候选 `ruleVersionId` 在 `rule_version` 已不存在(理论上不会,版本不可变保留) | 400 `REPLAY_VERSION_MISSING`,列出缺失版本 id |
| 回放评估自身出错 | 同常规评估错误语义(`EvalResult.error(errorCode)` + trace),如实返回 |

## 8. 测试策略

遵循项目测试纪律(`mvn-env`,`$MVN -pl <module> -am test`,收尾 `clean test`)。

- **单元**:
  - `context_snapshot` 反序列化:`{metrics,evalNow}` → `Map<String,MetricValue>` + `Instant`;null/缺字段处理。
  - `EvalEngine.evaluateReplay`:给定冻结 metrics + evalNow + 候选,**不触发取数**(注入 stub 取数 handler 断言零调用),输出与同输入常规评估一致。
  - `ReplayService`:快照缺失 → `REPLAY_NOT_REPRODUCIBLE`;版本缺失 → `REPLAY_VERSION_MISSING`;只读(断言不写 session/dry_run_session)。
  - 落库:`AuditPersister` 写入 `payload` + `candidate_rule_version_ids`(从 `AuditRecordedEvent` 取)。
- **集成(功能端到端,涉 DB schema + 落库链路,按 CLAUDE.md 功能测试纪律)**:
  1. 配置 scene/rule(带一个 metric 条件)→ 真实评估一个事件 → 查 `evaluation_session` 确认 payload + 候选 id + context_snapshot 真落库。
  2. 改动该 metric 的当前取数值(或换 handler 返回不同值)→ `POST .../replay` → 断言 replay 结果用的是**历史 metric**(与当时一致),而非当前值;trace 与当时一致。
  3. 存量行(手造无两列)replay → `REPLAY_NOT_REPRODUCIBLE`。
  4. 清理测试数据。

## 9. 决策日志条目(D70)

**D70 忠实重放:历史评估的时间旅行式重现 | A**

给历史 `evaluation_session` 加**忠实重放**:锁当时规则版本 + 灌当时 payload/metric/evalNow + 跳过取数,重跑出与当时一致的 `EvalResult`+trace,**只读零副作用**(不落新 session、不触发下游)。**落库侧**:`evaluation_session` 加 `payload`、`candidate_rule_version_ids` 两列(V1_31,均可空兼容存量;`AuditRecordedEvent` 补候选版本 id),metric/evalNow 复用现有 `context_snapshot`,规则版本复用不可变版本库(D56 SUPERSEDED 保留)。**捕获开关默认开**:三件套(payload+候选id+context_snapshot)捕获并入 `engine.rule.audit.context-snapshot.enabled`,默认由关改**开**(session 默认可重放,可经配置关闭)。**回放侧**:`POST /admin/v1/evaluation-sessions/{sessionId}/replay` + `ReplayService` + 新引擎入口 `EvalEngine.evaluateReplay`(抽 `evaluate0` 共享核心;degraded `EvalContextAssembler` 把冻结 metric 当 providedMetrics 回灌、绕过取数——与 dry-run 重新取数的本质区别)。**忠实度边界**:subject 暂重载当前(条件引用主体属性时可能偏差,后续扩 snapshot)、metric 仅存 rawValue(丢 dataType/isError)、本特性上线前的存量 session 与 best-effort 丢失 snapshot 的 session 不可 replay(`REPLAY_NOT_REPRODUCIBLE`,不退化成当前数据)。**非目标**:what-if(历史数据×当前规则)留后续(复用本设计快照回灌,改锁版本为取当前版本)。**PII**:payload 默认存,保留期对齐 session TTL。设计见 `specs/2026-06-12-faithful-replay-design.md`。
