# Design: 开放 DECISION_FLOW 的 RuleRefNode 跨 Scene 引用规则

## 架构决策

### D1. ruleCode 在 tenant 内唯一（不改 DDL）

`rule_definition` 表已有 `UNIQUE KEY uk_tenant_code (tenant_id, code)`（`V1_0__init_schema.sql:17`），约束早就到位，历史代码未执行这个语义。**零 DDL 变更**。

`rule_definition.scene_id` 保留为"主归属 Scene"外键（用于列表展示/默认 payloadSchema 校验），不参与跨 Scene 引用查找。

### D2. 查询键从 (tenant, scene, code) 改为 (tenant, code)

新增 `RuleDefinitionMapper.findByTenantAndCode(tenantId, code)` Mapper default 方法（🟢 `RuleDefinitionMapper.java`，仿 `findBySceneAndCode` 签名）。

原 `findBySceneAndCode` 只保留 `RuleImportService`（按场景导入的正确语义），其余调用点全换。

### D3. 被引规则快照 sceneCode 用被引规则的归属 scene

`PublishService.freezeReferencedRule` L659 当前写 `scene.getCode()`（flow 所属 scene）——**错误**，改为：

```java
SceneDef refScene = sceneMapper.selectById(ref.getSceneId());  // 被引规则的归属 scene
return new RuleVersionSnapshot(
    active.getId(), refScene.getCode(), ...);  // 用 refScene.getCode()
```

评估期 FlowExecutor 从冻结快照直接执行（不走倒排索引路由），所以此修正不影响评估语义，只让快照语义更准确。

### D4. 对外 API sceneId 泄漏修复

`RuleBundleController` 的 `?sceneId=Long` 是内部代理键泄漏，改为 `?sceneCode=String`，内部由 controller 调 `sceneMapper.findByCode(tenantId, sceneCode)` 转换。

### D5. 反向血缘：rule→flow 查询策略

遍历 tenant 下全部 ACTIVE `DECISION_FLOW` 规则快照，从 `FlowBody.referencedSnapshots` 的 key 集合匹配 `ruleCode`。不建专用索引表（v1 规模下在线扫描可接受，按需查而非常驻）。

### D6. 前端 RuleRef 下拉：tenant 全量 + sceneCode 分组

- API 调用：`listRules(tenantId, undefined, { page: 1, size: 500, excludeKind: 'DECISION_FLOW' })`（排除 DECISION_FLOW 防递归引用配置）
- UI：Select 用 `optionGroupLabel` 按 sceneCode 分组，选项格式 `规则名称 (code)`
- `flowSceneRules` store 字段语义从"场景内规则"升级为"租户内可引用规则"

## 完整改动清单

| 文件 | 动作 | 说明 |
|---|---|---|
| `config/repository/RuleDefinitionMapper.java` | 新增方法 | `findByTenantAndCode(tenantId, code)` |
| `config/publish/PublishService.java` | 修改 3 处 | L646 查询改 / L659 sceneCode 修正 / L892 code 唯一性校验改 |
| `config/bundle/RuleImportService.java` | 修改 1 处 | L145 导入查重改为 tenant 级 |
| `config/lineage/RuleLineageService.java` *(新增接口)* | 新增 | `findFlowsReferencingRule(tenantId, ruleCode)` |
| `config/lineage/RuleLineageServiceImpl.java` *(新增实现)* | 新增 | 遍历 ACTIVE DECISION_FLOW 快照 |
| `web/admin/RuleBundleController.java` | 修改 | `?sceneId=Long` → `?sceneCode=String` |
| `web/admin/RuleController.java` 或新增 endpoint | 修改/新增 | `GET /admin/v1/rules/{code}/referencedBy` 返回引用此规则的 flow 列表 |
| `frontend/CenterPanel.tsx` | 修改 | flowSceneRules 改为 tenant 全量 |
| `frontend/FlowNodeInspectorDrawer.tsx` | 修改 | RuleRef 下拉 + sceneCode 分组 |
| `frontend/FlowCanvasEditor.tsx` | 修改 | 新建叶子规则 scene 弹选（默认当前 scene） |

## 不改的

- kernel / FlowExecutor / SceneRuleIndex / eval-svc 评估链路（全部零改动）
- `rule_definition.scene_id` 外键（保留为主归属 scene）
- `rule_definition` / `scene` 表结构（DDL 零变更）
- `RuleVersionReadMapper` SQL 里的 `INNER JOIN scene s ON rd.scene_id = s.id`（内部实现保持）
