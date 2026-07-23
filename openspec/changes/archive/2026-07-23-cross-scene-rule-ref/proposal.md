# Proposal: rule_definition 去 scene_id + DECISION_FLOW RuleRef 跨 Scene 引用

**Change**: `cross-scene-rule-ref`
**Status**: PROPOSED
**Date**: 2026-07-23

## Why

两个强耦合问题合并交付：

**问题 1 — `rule_definition.scene_id` 是内部代理键污染**
- `scene.code` 已是所有对外 API 和运行时（`RuleEvent.sceneCode`、`SceneRuleIndex` key、eval-svc SQL）的第一公民
- `scene_id` 只是"DB join 快"的局部优化，代价是 config-svc 服务层到处做 `sceneMapper.findByCode → scene.getId()` 翻译
- 翻译层导致 `RuleBundleController` 等地方泄漏内部代理键到外部 API

**问题 2 — RuleRefNode 只能引用同 Scene 规则**
- 公共逻辑（"黑名单命中"、"新账户 < 90 天"）必须在每个 Scene 重复配置
- 修改时需逐 Scene 同步，易漏易产生版本漂移
- 根因正是 `findBySceneAndCode(tenantId, scene.getId(), ruleCode)` 的 `sceneId` 参与了查找

两个问题同根同治：消灭 `scene_id`，统一用 `scene_code`。

## What

### 1. `rule_definition.scene_id → scene_code`（DDL 变更）
- `V1_41`：`rule_definition` DROP `scene_id`，ADD `scene_code VARCHAR(64)`
- 唯一约束已是 `uk_tenant_code (tenant_id, code)`，零新增约束
- 不改其他表（`scene_metric_binding`/`scene_action_binding`/`scene_payload_schema_history` 均已 DROP）

### 2. 消灭 `sceneId` 翻译层（config-svc）
- `PublishService.createDraft`：去掉 `sceneMapper.findByCode → scene.getId()` 翻译，直接存 `scene_code`
- `PublishService.freezeReferencedRule`：查询改 `findByTenantAndCode(tenantId, ruleCode)`，被引规则快照 sceneCode 用被引规则自己的 scene_code
- `PublishService.createDraft` code 唯一性校验改为 tenant 级
- `RuleImportService`：导入查重改为 tenant + code
- `RuleAnalysisServiceImpl`：`findByTenantAndSceneIds` → `findByTenantAndSceneCode`
- `MetadataServiceImpl`：同上

### 3. 开放 RuleRefNode 跨 Scene 引用
- `RuleDefinitionMapper.findByTenantAndCode(tenantId, code)` 替代 `findBySceneAndCode`
- 发布期按 `(tenantId, ruleCode)` 查被引规则，不再限制同 Scene

### 4. eval-svc SQL 更新
- `RuleVersionReadMapper` 3 处 JOIN 改为 `JOIN scene s ON rd.tenant_id = s.tenant_id AND rd.scene_code = s.code`

### 5. 对外 API 清理
- `RuleBundleController`：`?sceneId=Long` → `?sceneCode=String`

### 6. 反向血缘（新增）
- `GET /admin/v1/rules/{code}/referencedBy?tenantId=` 返回引用此规则的 flow 列表

### 7. 前端
- RuleRef 下拉扩展为 tenant 全量 + sceneCode 分组
- 新建叶子规则 scene 弹选（默认当前 flow 所属 scene）

### Goals
- `rule_definition` 不再存 `scene_id`，统一用 `scene_code`
- config-svc 消灭 `sceneCode→sceneId` 翻译层
- DECISION_FLOW RuleRefNode 可引用同 tenant 任意 Scene 的规则
- `ruleCode` 作为 tenant 级业务标识唯一存在

### Non-Goals
- 不改 tenant_id（BIGINT 内部标识，保留）
- 不改 kernel / FlowExecutor / SceneRuleIndex / 评估链路
- 不改 scene 表本身
- 不实现参数化模板（D74）

## Impact

### DDL
- `V1_41`：`rule_definition` DROP `scene_id`，ADD `scene_code VARCHAR(64) NOT NULL`

### config-svc
- `RuleDefinition` 实体：`sceneId → sceneCode`
- `RuleDefinitionMapper`：新增 `findByTenantAndCode`，`findBySceneAndCode` 改签名/废弃，`findByTenantAndSceneIds` → `findByTenantAndSceneCode`
- `PublishService`：消灭 `scene.getId()` 翻译，3 处改动
- `RuleImportService`：查重改 tenant 级
- `RuleAnalysisServiceImpl`、`MetadataServiceImpl`：查询改 scene_code
- `RuleLineageService`（新增）：反向血缘查询

### eval-svc
- `RuleVersionReadMapper`：3 处 SQL JOIN 改写

### rule-api
- `RuleBundleController`：`?sceneId=` → `?sceneCode=`
- 新增 `GET /admin/v1/rules/{code}/referencedBy`

### 前端
- `CenterPanel`、`FlowNodeInspectorDrawer`、`FlowCanvasEditor`：RuleRef 下拉 tenant 全量 + 分组
