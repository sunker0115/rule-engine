# 规则版本管理设计

> 日期：2026-06-16
> 缺口：rule 历史版本前端只能看元信息（版本号/时间/状态），看不到内容、不能 diff、不能回滚。后端无"按 versionId 查完整快照"端点、无 diff、无原地回滚（仅 `newVersion(fromVersionId)` 克隆草稿）。
> 范围：**仅 rule**。metric 版本管理经评审 YAGNI 出局（见 §6）。

## 1. 现状（building block 大半已存在）

- `rule_version` 表存**不可变版本行**（conditionAst / decisionBindings / preGates / triggerEventTypes / scriptSource / kind / version / status DRAFT·ACTIVE·SUPERSEDED·DISABLED）；`rule_definition` 存身份 + current_version 指针。rule 已是「不可变版本快照 + 当前指针」的正确模型。
- `ConfigService.getRuleDetail` 返回**当前版本**完整内容 + 版本时间线元信息（`RuleDetailVO.VersionItem`：id/version/status/createdAt/publishedBy/publishedAt）。历史版本只给元信息、不给内容。
- `newVersion(tenantId, ruleId, …, fromVersionId, …)`（`POST /admin/v1/rules/{ruleId}/versions`）**已实现克隆某历史版本内容为新草稿**，注释明示"为回退"，且**按当前世界重解析**（克隆内容拿到今天的 metric/decision/scene 重新解析校验）。要求"当前无未发布 DRAFT"。激活仍走显式 `publish`。
- `RuleVersionMapper`（config-svc）可 `selectById(versionId)` 取 RuleVersion 实体（含全部 typed 内容）。
- 前端审计日志页已有 before/after JSON diff 查看器（`audit-log/index.tsx`）。
- 前端版本时间线（`rule-editor/LeftPanel.tsx`、`RuleDetailDrawer.tsx`）条目**不可点**。

## 2. 决策

| # | 决策 | 理由 |
|---|---|---|
| 1 | 仅 rule，metric 出局 | metric 回滚价值低（fix-forward 即可、存量规则版本冻结爆炸半径小）；见 §6 |
| 2 | view + diff 共用**一个**"取版本内容"读端点；diff 走前端 JSON diff | 复用现有审计 diff 组件；语义 diff 对 v1 过度设计 |
| 3 | 回滚**两步式**（复用 `newVersion(fromVersionId)` → 编辑器 review → publish），不加回滚端点、不做一键发布 | 与项目"显式发布 + 发布期校验"安全基调一致；回滚本质=基于旧版开新版再发布；一键式在"旧版引用已失效"时翻车 |
| 4 | rule-write 三方法参数对象化（抽 `RuleContent`） | CLAUDE.md §5（≥4 参→对象）；以后加规则字段不再改满地文件 |

## 3. 后端设计

### 3.1 查看版本内容端点（唯一新增端点）

- `GET /admin/v1/rules/{ruleId}/versions/{versionId}?tenantId=` → 该版本完整内容。
- `ConfigService.getRuleVersion(String tenantId, Long ruleId, Long versionId) → RuleVersionContentVO`。
- 实现：`ruleVersionMapper.selectById(versionId)` 取 RuleVersion；校验 `version.ruleDefinitionId == ruleId` 且 rule 归属 tenant（否则 `IllegalArgumentException`）。
- `RuleVersionContentVO`（typed record，复用 `RuleDetailVO` 内容字段形状）：`ruleVersionId, version, status, kind, conditionAst(AstNode), decisionBindings(List<DecisionBinding>), preGates(List<PreGateConfig>), triggerEventTypes(List<String>), scriptSource(ScriptSource), createdAt, publishedBy, publishedAt`。typed 内容直返，不转 String。
- **此端点同时支撑 view（取一版）与 diff（前端取两版）**。对当前版本同样适用（统一入口）；`getRuleDetail` 保持不动（其内嵌当前内容仍可用）。
- controller：`RuleController` 加该 GET。路径 `{ruleId}/versions/{versionId}` 与既有 `DELETE {ruleId}/versions/{versionId}`、`POST {ruleId}/versions` 不冲突（方法不同）。

### 3.2 rule-write 方法参数对象化（决策 4）

抽 typed record `RuleContent`（config-svc api）承载三方法共有的**内容字段**：
```
record RuleContent(
    String name, String kind,
    AstNode conditionAst,
    List<DecisionBinding> decisionBindings,
    List<PreGateConfig> preGates,
    List<String> triggerEventTypes,
    ScriptSource script) {}
```
`ConfigService` 三方法签名收敛（身份/控制参数保留，内容入 `RuleContent`）：
- `createDraft(String tenantId, String sceneCode, String code, RuleContent content, String actorId)`
- `editDraft(String tenantId, Long ruleId, RuleContent content, String actorId)`
- `newVersion(String tenantId, Long ruleId, RuleContent content, Long fromVersionId, String actorId)`

`RuleController` 三端点把请求 DTO（CreateRuleRequest/EditDraftRequest/NewVersionRequest）映射为 `RuleContent`（含现有 `DecisionBindingInput→DecisionBinding` priority 占位映射，集中到一处）。`ConfigServiceImpl` 实现内部解包 `RuleContent`。**以后加规则内容字段只改 `RuleContent` record + DTO 映射，不动三方法签名与调用点。**

> 这是既有方法重构，行为不变——重构前后所有 rule 写测试必须不回归（`createDraft`/`editDraft`/`newVersion` 现有测试）。

### 3.3 回滚：复用 `newVersion(fromVersionId)`，不加端点

回滚是前端编排（§4.3），后端零新增：克隆 → 编辑器 review → publish 全走现有 `newVersion(fromVersionId)` + `publish`。

## 4. 前端设计

### 4.1 版本时间线交互化

`rule-editor/LeftPanel.tsx` 与 `RuleDetailDrawer.tsx` 的版本时间线条目从不可点 → 每条带动作：**查看内容 / 与当前对比 / 恢复此版本**。

### 4.2 查看 + diff
- **查看内容**：点条目 → `GET /rules/{ruleId}/versions/{versionId}` → 只读展示该版本（复用编辑器只读渲染或结构化查看器；scriptSource 类显脚本，AST 类显条件树只读）。
- **与当前对比(diff)**：取该历史版本 + 当前版本两份内容 → JSON diff 渲染（复用/扩展 `audit-log` 的 before/after diff 查看器，喂两份 JSON）。入口：历史版本条目"与当前对比"。
- api：`api/rule.ts` 加 `getRuleVersion(ruleId, versionId, tenantId)`。

### 4.3 回滚（两步式）
- 历史版本条目"恢复此版本" → `POST /rules/{ruleId}/versions {fromVersionId: 该版本id, ...}` 克隆为新草稿 → 拿到新草稿 ruleId/version → **导航进规则编辑器**展示重解析后内容供 review → 用户在编辑器 publish 生效。
- 复用既有 `newVersion` api + 编辑器 + publish，无新流程。

### 4.4 约束 + 错误处理（设计要点）
1. **已有未发布草稿时不能回滚**：`newVersion` 要求"当前无 DRAFT"。前端"恢复此版本"前若检测到已有 DRAFT 版本（时间线里有 DRAFT 状态条目）→ 明确提示"已有未发布草稿，请先发布或删除再恢复"，禁用恢复动作，不静默调用失败。
2. **回滚=按当前世界重解析**：旧版引用的 metric/decision 现已失效 → 克隆重解析失败/有差异。后端 `newVersion` 重解析失败时透出错误；前端把该错误清晰展示（"恢复失败：旧版引用的 X 已不存在/已变更"），用户据此决定改后再发。
3. 版本不存在 / 跨租户 / 版本不属该 rule → 400/404，前端提示。

## 5. 测试

- **后端**：
  - `getRuleVersion`：正常取完整 typed 内容（AST/bindings/script 都在）；跨租户拒；versionId 不属该 ruleId 拒；不存在拒。
  - `RuleContent` 重构：`createDraft`/`editDraft`/`newVersion` 现有测试改走 `RuleContent` 入参、行为不回归（含 `newVersion(fromVersionId)` 回滚克隆既有用例）。
  - controller 层 GET 版本端点（200 + typed 内容 / 400）。
- **前端**：构建通过；时间线动作（查看/diff/恢复跳转）、diff 渲染、已有草稿时恢复禁用提示——构建 + 逻辑验证（无法视觉验证项标注）。
- **功能 e2e（起服务）**：建规则→发布 v1→改内容发布 v2→查看 v1 内容（真取历史快照）→v1 与 v2 diff→恢复 v1（克隆草稿→编辑器→发布 v3）；验已有草稿时恢复被拦。清理测试数据。

## 6. 非目标（YAGNI）

- **metric 版本管理**：评审结论——metric 回滚价值低（恢复=fix-forward 再 edit；存量规则绑冻结版本、爆炸半径小；metric 内容是简单字段，照旧值重填低摩擦）。metric **不拆历史表、不做不可变化重构、不做回滚**。最多以后做个小增强：把 metric 审计快照记全 before/after 内容，让现有审计 diff 覆盖 metric 改动——低优先、单独一条、不在本 spec。
- **语义 diff**（逐字段人话差异）：v1 用 JSON diff 即可，语义 diff 留增强。
- **一键回滚**（克隆+自动发布）：跳过 review、旧版失效时翻车，不做。
- **原地 rollback**（不经 newVersion 克隆直接改 current 指针）：破坏不可变快照模型，不做。
