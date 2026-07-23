# Proposal: 开放 DECISION_FLOW 的 RuleRefNode 跨 Scene 引用规则

**Change**: `cross-scene-rule-ref`
**Status**: PROPOSED
**Date**: 2026-07-23

## Why

当前 `DECISION_FLOW` 规则的 `RuleRefNode` 只能引用**同一 Scene** 下的规则（`PublishService.freezeReferencedRule` L646 硬限制）。这导致：
- 公共业务逻辑（如"账户开立 < 90 天"、"黑名单命中"）必须在每个引用它的 Scene 下复制一份
- 多处复制 → 修改时需逐 Scene 同步 → 易漏易产生版本漂移
- DECISION_FLOW 的编排能力无法跨 Scene 复用叶子规则

## What

1. **开放 `RuleRefNode` 跨 Scene 引用**：`findBySceneAndCode` → `findByTenantAndCode`，`ruleCode` 在 tenant 内唯一（已有 DDL 约束 `uk_tenant_code`，零 DDL 变更）
2. **修正被引规则快照的 `sceneCode`**：冻快照时用被引规则自己所属的 sceneCode，而非 flow 的 sceneCode
3. **修正对外 API 的 sceneId 泄漏**：`RuleBundleController` 的 `?sceneId=` 改为 `?sceneCode=`
4. **新增 rule→flow 反向血缘查询**：改规则时能看到哪些 flow 引用了它
5. **前端 RuleRef 下拉扩展为 tenant 全量**：加 sceneCode 分组展示

### Goals
- DECISION_FLOW 的 RuleRefNode 可引用同 tenant 下任意 Scene 的规则
- `ruleCode` 作为 tenant 级业务标识唯一存在（不需改 DDL）
- 被引规则快照的 sceneCode 反映真实归属，不是 flow 的 sceneCode
- 暴露 rule→flow 反向血缘，支持"改规则 → 知影响哪些 flow"

### Non-Goals
- 不改 kernel / FlowExecutor / SceneRuleIndex / eval-svc 评估链路
- 不改 rule_definition / scene 表结构
- 不实现"规则定义脱离 Scene"——规则仍有主归属 Scene（scene_id 外键保留）
- 不实现参数化模板（D74）

## Impact

### 修改
- `RuleDefinitionMapper`：新增 `findByTenantAndCode(tenantId, code)`
- `PublishService.freezeReferencedRule`：查询改 + sceneCode 修正（2 处）
- `PublishService.createDraft`：code 唯一性校验改为 tenant 级
- `RuleImportService`：导入查重改为 tenant 级
- `RuleBundleController`：`?sceneId=` → `?sceneCode=`

### 新增
- `RuleLineageService.findFlowsReferencingRule(tenantId, ruleCode)` 反向血缘
- 对应 API endpoint + 前端血缘展示

### 前端
- `CenterPanel`：flowSceneRules 从 sceneCode 级 → tenant 级（加 sceneCode 分组）
- `FlowNodeInspectorDrawer`：RuleRef 下拉改为 tenant 全量 + sceneCode 分组
- 新建叶子规则时 scene 归属弹选（默认当前 flow 所属 scene）
