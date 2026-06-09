# Pre-Gate 收敛 — 设计待办

> 状态:方向已定,待执行(2026-06-09 讨论达成)。非新功能,属契约收敛 + 一处堵漏,不需要逐步 plan,按下方清单直接做。

## 决策

Pre-Gate 最终只保留 **ROLLOUT** 一个 gate(无状态灰度);移除 RATE_LIMIT/MUTEX;黑白名单改走 **BOOLEAN metric + condition**,不再是 pre-gate。

## 理由

- **RATE_LIMIT / MUTEX**:有状态(计数窗口 / 并发锁),需要引擎持有分布式状态(Redis),打破当前"无状态评估"假设。greenfield 阶段不引入该架构依赖;当前它们是静默 fail-open 的伪能力,砍掉。未来真要做分布式状态时单独设计。
- **WHITELIST / BLACKLIST**:本质是"按 subjectId 查一份数据判成员",这正是 metric 的活。两个原因不该放 pre-gate:① 时序——pre-gate 在 EvalContext 装配**之前**,而 metric 在 Context 阶段才取数,pre-gate 拿不到 metric;② 语义——查数据判定就该走 metric 治理体系(取数/缓存/版本/allowProvided/影响面)。
  - 落地形态:名单 metric(`sourceType=SQL_AGGREGATE`,`dataType=BOOLEAN`,SQL `SELECT EXISTS(... WHERE list_key=:listKey AND subject_id=:subjectId)`,subjectId 取数自动带入)+ 规则里 `EQ(in_blacklist, true)` / `EQ(in_whitelist, false)`。复用现有 metric 机制,**无需写 pre-gate 代码**。
- **ROLLOUT**:murmur3 分桶,无状态、无依赖,是 pre-gate 真正适合的唯一场景,保留。

## 落地清单

**文档(契约收敛)**
- `00-decisions.md` 追加一条决策(D22 历史条目不改;新追加说明 Pre-Gate 收敛为仅 ROLLOUT、移除 RATE_LIMIT/MUTEX、黑白名单转 metric+condition、记下方语义取舍)。
- `01-concepts` / `02-runtime` / `05-storage` / `06-frontend` / `07-operability`:gate 列表、`blocked_by` 合法取值、`rule_engine_eval_blocked_total{gate_type}` label —— 全部收敛到只剩 `ROLLOUT`。
- archive 下旧 examples 不动。

**代码(堵 fail-open)**
- `EvalEngine.applyPreGates`(rule-kernel):`if (gate == null) continue`(静默放行)→ **fail-closed**(未注册 gateType 视为拦截 / 报错)。
- 发布期校验(PublishService):`pre_gates[].gateType` 必须有注册的 PreGate 实现(现仅 ROLLOUT 合法),配已砍/未实装 gate 一律发布拒绝。
- 测试:配未知 gateType → 发布拒绝;配 ROLLOUT → 正常;运行期未注册 gate → fail-closed 拦截。

**收尾**
- `doc-consistency-review` skill 扫 00/01/02/05/06/07 自洽。
- 全量 `clean test`。
- (改 docs 与 rule-* 代码)显式调用 `rule-engine-reviewer` 审代码↔文档对齐。

## 语义取舍(已接受)

1. **对账**:黑白名单拦截从 `BLOCKED`(pre-gate)变为 `MISS`(condition 不满足),`blocked_by` 不再有 WHITELIST/BLACKLIST。要区分靠 node_trace 看具体 condition。
2. **短路**:失去"白名单用户直接跳过评估"的优化,白名单用户也进评估、多取一个 BOOLEAN metric(开销可控)。

## 与其它待办的关系

- 与 `2026-06-09-payload-direct-reference` 独立,无先后依赖。
- 黑白名单的"name_list 表 + 名单管理 UI"是更后面的事,本收敛不含;当前用 SQL_AGGREGATE metric 即可配出黑白名单。
