# Tasks: cross-scene-rule-ref

> 状态：**全部完成**（2026-07-24，见 00-decisions D77）。config-svc 431 + eval-svc 324 + rule-api Controller 27 测试通过（含 Testcontainers 集成测试真实执行）；前端 `tsc --noEmit` + `vite build` 通过。
> 说明：T3「PublishService 跨 scene + 快照 sceneCode 正确性」与「code tenant 级唯一」由既有 `FlowResolveValidateTest`（跨 Scene 用例反转为成功冻结、断言 `refSnap.sceneCode()` 取被引规则 sceneCode）+ `PublishServiceTest.createDraft_duplicateCode_throwsIllegalArgument` 覆盖，未另建重复测试。

## T1. DDL + 实体

- V1_41：rule_definition DROP scene_id，ADD scene_code
- RuleDefinition.java：sceneId Long → sceneCode String
- RuleDefinition.draft() 工厂：参数改 sceneCode

## T2. RuleDefinitionMapper 重构

- 新增 findByTenantAndCode(tenantId, code)
- findBySceneAndCode → findByTenantAndCode（删 sceneId 参数）
- findByTenantAndSceneIds → findByTenantAndSceneCode

## T3. PublishService 核心改动

- createDraft：去掉 scene.getId() 翻译，直接用 sceneCode
- freezeReferencedRule：查询改 + 快照 sceneCode 修正
- code 唯一性校验改为 tenant 级

## T4. 其余 config-svc 改动

- ConfigServiceImpl：去掉 sceneCode→sceneId 翻译
- RuleImportService：查重改 tenant 级
- RuleAnalysisServiceImpl / MetadataServiceImpl：查询改 scene_code
- RuleExportService：rd.getSceneCode() 替代 sceneById 查询

## T5. eval-svc SQL JOIN 改写

- RuleVersionReadMapper：3 处 JOIN 改写

## T6. 反向血缘

- RuleLineageService 接口 + RuleLineageServiceImpl
- GET /admin/v1/rules/{code}/referencedBy

## T7. rule-api

- RuleBundleController：?sceneId= → ?sceneCode=
- 新增 referencedBy endpoint

## T8. 前端

- CenterPanel：flowSceneRules tenant 全量
- FlowNodeInspectorDrawer：RuleRef 下拉 + sceneCode 分组
- FlowCanvasEditor：新建叶子规则 scene 弹选

## T9. 全量测试 + 验证

- PublishService 跨 scene + 快照 sceneCode 正确性单测
- eval-svc SQL JOIN 正确性测试
- 反向血缘单测
- 全量 clean test
