# 后端 API 缺口——前端联调发现

> 本文档整理前端联调过程中发现的 4 个后端 API 缺口，每个附带接口签名、前端诉求、建议实现方案。

---

## 1. GET /admin/v1/rules 时间范围过滤

**现状**：`?from=&to=` 参数被忽略，始终返回全量数据。

**前端诉求**：运营需要按规则发布时间（`publishedAt`）筛选，例如"6 月 1 日至 6 月 14 日发布的规则"。结合已有 `?status=` 和 `?sceneCode=` 参数，形成组合筛选能力。

**建议实现**：

```
GET /admin/v1/rules?tenantId=9001&from=2026-06-01&to=2026-06-15
```

- `from`：可选，ISO 日期格式，筛选 `published_at >= from`
- `to`：可选，筛选 `published_at < to + 1天`（即 to 当天 23:59:59）
- 不传则不做时间过滤，保持向后兼容
- 与已有 `status` / `sceneCode` 参数组合使用

**改动范围**：`RuleController` + `RuleService` 查询方法，SQL 加 `WHERE published_at >= ? AND published_at < ?`。

---

## 2. GET /admin/v1/tenants 租户列表

**现状**：接口不存在（404）。

**前端诉求**：Header 租户选择器需要拉取可选租户列表。当前前端硬编码了两条（loadtest + samples），新增租户不可见。

**建议实现**：

```
GET /admin/v1/tenants
```

**Response**：
```json
{
  "success": true,
  "data": [
    { "id": 9001, "code": "loadtest", "name": "Load Test Tenant" },
    { "id": 9100, "code": "samples", "name": "示例租户" }
  ]
}
```

**改动范围**：新增 `TenantController`，`SELECT id, code, name FROM tenant WHERE status = 'ACTIVE'`。

---

## 3. GET /admin/v1/evaluation-sessions/{sessionId} 单条详情

**现状**：接口不存在（404），只有列表接口和 trace 接口。

**前端诉求**：会话详情页需要展示 `eventType` / `subjectId` / `source` / `mode` / `evalDurationMs` / `blockedBy` / `errorCode` / `finalDecision` / `candidateRuleCount` / `hitRuleCount` 等字段，这些在列表接口中不返回。当前前端通过路由 state 从列表页传基础信息（仅 6 个字段），信息不完整。

**建议实现**：

```
GET /admin/v1/evaluation-sessions/{sessionId}?tenantId=9001
```

**Response**：列表接口的 6 个字段 + 上述全量字段（含 `contextSnapshot`）。

**改动范围**：`evaluation_session` 表查询 + DTO 扩展。如果某些字段在 `evaluation_session` 表不存在，返回 null 即可。

---

## 4. GET /admin/v1/rules 列表响应补充 kind 和 sceneCode

**现状**：列表接口只返回 `ruleDefinitionId` / `code` / `name` / `status` / `currentVersion` / `publishedAt`。`kind` 和 `sceneCode` 缺失。

**前端诉求**：规则列表需要展示"类型"列（AST_BOOLEAN / SCORECARD 等）。汇总页（`/rules`，不传 sceneCode）需要 sceneCode 来生成编辑链接。

`kind` 在 `rule_definition` 表，`sceneCode` 需要 JOIN `scene_definition`（或从 rule 的 scene 关联读取）。

**建议实现**：列表 VO 追加两个字段：

```json
{
  "ruleDefinitionId": 899,
  "code": "test-rule-01",
  "name": "测试规则",
  "kind": "AST_BOOLEAN",
  "sceneCode": "test.scene",
  "status": "PUBLISHED",
  "currentVersion": 1770,
  "publishedAt": "2026-06-14T11:48:33.271"
}
```

**改动范围**：`RuleController` 列表查询 SQL + 响应 VO。

---

## 优先级建议

| 优先级 | # | 原因 |
|--------|---|------|
| P0 | 4. kind + sceneCode | 规则列表页面有空白列，汇总页编辑链接不可用 |
| P0 | 1. 时间过滤 | 运营刚需，否则规则多了无法筛选 |
| P1 | 2. 租户列表 | 前端有硬编码 workaround，当前不影响使用 |
| P1 | 3. session 详情 | 前端有路由 state workaround，详情页缺少部分字段 |
