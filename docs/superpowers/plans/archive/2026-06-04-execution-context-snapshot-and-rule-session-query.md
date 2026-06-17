# 执行上下文快照 + 按规则查历史 Session 端点

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补齐两处缺口——① `evaluation_session` / `dry_run_session` 落 `context_snapshot`（EvalContext metrics 快照），解决 dry-run 重放时 metric 值用"当前值"而非"历史原始值"的问题；② 新增"按规则查历史 Session"查询端点，满足运营排障"找这条规则最近触发的 10 次执行"的刚需。

**背景：** 文章《SpringBoot + 规则执行日志 + 调试回放》提出 contextBefore/contextAfter 快照和按规则查 trace 历史两个诉求。对照现有设计：`node_trace` 已有节点粒度的 `actual_value`，但 EvalContext 整体的 metrics 取数值（如"当时 user.balance=9800"）没有持久化，重放时 metric 重新取数会拿到新值；"按规则查历史 session"端点在 `10-api-contract` 中缺失。

**影响文件：**

| 文件 | 操作 | 说明 |
|------|------|------|
| `docs/01-concepts.md` | 修改 | §3.15 / §3.16 字段表补 `context_snapshot` |
| `docs/05-storage.md` | 修改 | `evaluation_session` / `dry_run_session` DDL 补列；运营查询索引表补说明 |
| `docs/10-api-contract.md` | 修改 | §二总览 + §六 新增 6.4 按规则查 session 端点 |

---

## Task 1：`01-concepts.md` 补 `context_snapshot` 字段

**Files:**
- Modify: `docs/01-concepts.md`

- [ ] **Step 1：在 §3.15 EvaluationSession 字段表末尾（`eval_duration_ms` 之后）追加**

```markdown
| `context_snapshot` | nullable JSON；EvalContext 构建完成后对 `metrics` 取数结果的快照（`{metricCode: value}`），用于 dry-run 重放时还原历史 metric 值，避免重放时取到当前新值；EvalContext 构建失败（`status=ERROR, errorCode=METRIC_FETCH_FAIL`）时为 null |
```

- [ ] **Step 2：在 §3.15 "关键边界" 块末尾追加一条**

```markdown
- **`context_snapshot` 写入时机**：EvalContext 构建成功后、进入 AST 评估前，由评估线程同步写入 `evaluation_session` 行（与 session INSERT 同事务，开销为一次 JSON 序列化）；构建失败时置 null，不阻塞 session 落库；
```

- [ ] **Step 3：在 §3.16 DryRunSession "额外字段" 表末尾追加**

```markdown
| `context_snapshot` | 同 §3.15；dry-run 场景下为本次试算时真实取到的 metric 快照，方便运营对比"重放时 metric 值"与"当前 metric 值"的差异 |
```

- [ ] **Step 4：验证文档自洽**

检查 §3.15 关键边界里"同步写（D21）"段落是否需要注明 context_snapshot 也在同事务内（应已在 Step 2 覆盖，确认无冲突）。

---

## Task 2：`05-storage.md` 补 DDL 列 + 索引说明

**Files:**
- Modify: `docs/05-storage.md`

- [ ] **Step 1：`evaluation_session` DDL，在 `eval_duration_ms` 列之后、`UNIQUE KEY` 之前插入**

```sql
  context_snapshot JSON          COMMENT 'EvalContext metrics 取数快照，{metricCode: value}；构建失败时为 null（排障 / dry-run 重放用）',
```

- [ ] **Step 2：`dry_run_session` DDL，同位置插入相同列**

```sql
  context_snapshot JSON          COMMENT 'dry-run 试算时 EvalContext metrics 取数快照（排障 / 重放对比用）',
```

- [ ] **Step 3：运营查询索引表补说明**

在 `evaluation_session` 索引条目中，`idx_scene_subject` 行补充说明按规则查 session 的路由方式：

> 按规则查历史 session 走 `node_trace.rule_version_id → evaluation_session_id` JOIN，不直接在 evaluation_session 加规则索引（避免写热点；单次查询量小，JOIN 可接受）；详见 10-api-contract §6.4。

---

## Task 3：`10-api-contract.md` 新增 §6.4 端点

**Files:**
- Modify: `docs/10-api-contract.md`

- [ ] **Step 1：在 §二 接口分组总览表的"审计与查询"行**，将描述更新为：

```
| 审计与查询 | `/api/v1/evaluation-sessions`，`/api/v1/rules/{id}/sessions` | 查 session / trace / action 执行；按规则查历史触发记录 |
```

- [ ] **Step 2：在 §6.3 查询 audit_log 之后追加 §6.4**

```markdown
### 6.4 按规则查历史 evaluation_session

> 排障场景：运营在规则详情页快速看到"这条规则最近触发了哪些 session，结果如何"。

```
GET /api/v1/rules/{ruleDefinitionId}/sessions?tenantId=demo-tenant&status=HIT&limit=20&offset=0
```

**Path 参数：**

| 参数 | 说明 |
|------|------|
| `ruleDefinitionId` | 规则定义 ID（`rule_definition.id`） |

**Query 参数：**

| 参数 | 必填 | 说明 |
|------|------|------|
| `tenantId` | 是 | 租户 ID |
| `status` | 否 | 筛选终态：`HIT / MISS / BLOCKED / ERROR`；不传则返回全部终态 |
| `limit` | 否 | 每页条数，默认 20，最大 100 |
| `offset` | 否 | 偏移量，默认 0 |

**Response 200：**
```json
{
  "total": 135,
  "items": [
    {
      "sessionId": 10001,
      "eventId": "evt-001",
      "subjectId": "user-001",
      "status": "HIT",
      "finalDecision": "REVIEW",
      "evalDurationMs": 45,
      "startedAt": "2026-06-04T10:00:00.123+08:00",
      "ruleVersionId": 42
    }
  ]
}
```

**实现说明（不属于 API 契约，供实现参考）：**
- 查询路由：`node_trace.rule_version_id` IN（ruleDefinitionId 对应的所有 `rule_version.id`）→ 取 `evaluation_session_id` → JOIN `evaluation_session`；
- 排序：`evaluation_session.started_at DESC`；
- `ruleVersionId` 字段来自关联的 `node_trace.rule_version_id`（取该 session 内该规则的版本号）；
- 不支持跨租户查询（tenantId 为必填，查询层校验）。
```

- [ ] **Step 3：在文档状态表 §一 更新 §六 行状态**（当前已是 ✅，确认描述仍准确无需改动即跳过）

---

## 验证清单

- [ ] `01-concepts.md` §3.15 字段表含 `context_snapshot`，关键边界有写入时机说明
- [ ] `01-concepts.md` §3.16 额外字段表含 `context_snapshot`
- [ ] `05-storage.md` `evaluation_session` DDL 含 `context_snapshot JSON` 列
- [ ] `05-storage.md` `dry_run_session` DDL 含 `context_snapshot JSON` 列
- [ ] `05-storage.md` 运营查询索引表有按规则查 session 的路由说明
- [ ] `10-api-contract.md` §二总览更新
- [ ] `10-api-contract.md` §6.4 端点完整（路径 / 参数 / 响应 / 实现说明）
- [ ] 三个文档内部互相引用的链接无断链

---

## 实装状态

| Task | 内容 | 状态 |
|------|------|------|
| Task 1 | 01-concepts 补 context_snapshot 字段 | 待实装 |
| Task 2 | 05-storage DDL 补列 + 索引说明 | 待实装 |
| Task 3 | 10-api-contract 新增 §6.4 | 待实装 |
