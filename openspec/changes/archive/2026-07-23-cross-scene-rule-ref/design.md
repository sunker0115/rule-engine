# Design: rule_definition 去 scene_id + RuleRef 跨 Scene 引用

## 架构决策

### D1. rule_definition.scene_id → scene_code，消灭代理键翻译层

`scene.code` 已是运行时第一公民（`RuleEvent.sceneCode`、`SceneRuleIndex` key `"tenantId:sceneCode"`、eval-svc SQL `s.code AS sceneCode`）。`scene_id` 是唯一一个在内部逻辑（非 API 边界）被当业务键用的数值代理键，造成服务层到处翻译。

改法：`rule_definition` DROP `scene_id`，ADD `scene_code VARCHAR(64) NOT NULL`。其他有 `scene_id` 的表（`scene_metric_binding`/`scene_action_binding`/`scene_payload_schema_history`）均已 DROP（V1_22/V1_23/V1_30），无需处理。

### D2. tenant_id 保留 BIGINT，不改

`tenant_id` 出现在每张表，是全局高频 join 键，数值型性能优先。`tenantCode` 只在外部 API 边界翻译一次，这个边界是正确的，不动。

### D3. ruleCode 在 tenant 内唯一（DDL 已就位）

`rule_definition` 已有 `UNIQUE KEY uk_tenant_code (tenant_id, code)`（`V1_0__init_schema.sql`），约束早就到位，只是代码未执行这个语义。**零新增约束**。

### D4. eval-svc SQL JOIN 改写

原：`INNER JOIN scene s ON rd.scene_id = s.id`  
改：`INNER JOIN scene s ON rd.tenant_id = s.tenant_id AND rd.scene_code = s.code`

三处（`loadAllActive` / `loadActiveByScene` / `loadById`），`RuleVersionReadMapper.java`。

### D5. 被引规则快照 sceneCode 用被引规则自己的 scene_code

原 `PublishService.freezeReferencedRule` L659 写 `scene.getCode()`（flow 的 scene）——错误，改为 `ref.getSceneCode()`（被引规则的 scene_code 字段直接读）。

### D6. 反向血缘遍历策略

遍历 tenant 下全部 ACTIVE `DECISION_FLOW` 规则版本的 `FlowBody.referencedSnapshots` key 集合，匹配 ruleCode。不建专用索引表（在线按需扫描，v1 规模可接受）。

---

## 完整改动清单

### DDL（1 个迁移文件）

| 文件 | 内容 |
|---|---|
| `V1_41__rule_definition_scene_code.sql` | DROP COLUMN `scene_id`；ADD COLUMN `scene_code VARCHAR(64) NOT NULL`；DROP KEY `idx_scene_id`；ADD KEY `idx_tenant_scene` |

### config-svc

| 文件 | 改动 |
|---|---|
| `RuleDefinition.java` | `sceneId Long` → `sceneCode String` |
| `RuleDefinitionMapper.java` | 新增 `findByTenantAndCode`；`findBySceneAndCode` 签名改为 `findByTenantAndCode`（`sceneId` 参数删除）；`findByTenantAndSceneIds` → `findByTenantAndSceneCode(tenantId, sceneCode)` |
| `PublishService.java` | `createDraft`：去掉 `scene.getId()` 翻译，`RuleDefinition.draft` 改传 `sceneCode`；`freezeReferencedRule`：查询改 `findByTenantAndCode`，快照 sceneCode 改 `ref.getSceneCode()`；code 唯一性校验改 tenant 级 |
| `RuleDefinition.draft()` 静态工厂 | 参数 `sceneId Long` → `sceneCode String` |
| `ConfigServiceImpl.java` | `listRules`：`sceneCode→sceneId` 翻译删除，直接传 `sceneCode` |
| `RuleImportService.java` | 查重改 `findByTenantAndCode` |
| `RuleAnalysisServiceImpl.java` | `findByTenantAndSceneIds` → `findByTenantAndSceneCode` |
| `MetadataServiceImpl.java` | 同上 |
| `RuleExportService.java` | `rd.getSceneId()` → `rd.getSceneCode()`；去掉 `sceneById` map 查询 |
| `RuleLineageService.java`（新增接口） | `findFlowsReferencingRule(tenantId, ruleCode)` |
| `RuleLineageServiceImpl.java`（新增实现） | 遍历 ACTIVE DECISION_FLOW 快照的 referencedSnapshots |

### eval-svc

| 文件 | 改动 |
|---|---|
| `RuleVersionReadMapper.java` | 3 处 SQL：`JOIN scene s ON rd.scene_id = s.id` → `JOIN scene s ON rd.tenant_id = s.tenant_id AND rd.scene_code = s.code` |

### rule-api

| 文件 | 改动 |
|---|---|
| `RuleBundleController.java` | `?sceneId=Long` → `?sceneCode=String` |
| `RuleController.java` / 新增 endpoint | `GET /admin/v1/rules/{code}/referencedBy?tenantId=` |

### 前端

| 文件 | 改动 |
|---|---|
| `CenterPanel.tsx` | `listRules(tenantId, sceneCode)` → `listRules(tenantId, undefined)` + tenant 全量 |
| `FlowNodeInspectorDrawer.tsx` | RuleRef 下拉 tenant 全量 + sceneCode 分组 |
| `FlowCanvasEditor.tsx` | 新建叶子规则 scene 归属弹选 |

### 不改的

- kernel / FlowExecutor / SceneRuleIndex / eval-svc 评估链路
- `scene` 表本身
- `tenant_id`（所有表保留 BIGINT）
- `rule_definition.uk_tenant_code` 约束（已就位）
