# API 三类拆分与契约规范化(admin / api / sdk)

> 目标:把 rule-api 的 HTTP 接口按受众分成 admin / api / sdk 三类,通过**包**和**路径前缀**区分;同时统一分页、消除裸 Map 返回、对齐字段类型、补齐前端必需读接口。greenfield 阶段无生产数据,采用破坏式重构,不保留旧路径兼容。

## 分类与前缀

| 类别 | 受众 | 包 | 路径前缀 | controller |
|---|---|---|---|---|
| admin | 管理后台(人) | `web.admin` | `/admin/v1/**` | Rule / Scene / Metric / Metadata / RuleBundle / Audit |
| api | 业务调用方 / 前端 | `web.api` | `/api/v1/**` | Eval |
| sdk | 嵌入式 SDK poller(机器) | `web.sdk` | `/sdk/v1/**` | SdkSnapshot / SdkMetricDefinition |

SDK 端点去掉冗余中缀:`/sdk/v1/snapshots`、`/sdk/v1/metric-definitions`。

---

## 阶段 1 — 结构调整(纯重构,不改行为)

### 包重组(rule-api)
迁移(旧文件删除 + 新包新建,改 package 声明 + `@RequestMapping` 前缀):
- `web.config.{Rule,Scene,Metric,Metadata,RuleBundle}Controller` → `web.admin.*`,前缀 `/admin/v1/...`
- `web.audit.AuditController` → `web.admin.AuditController`,`@RequestMapping("/admin/v1")`
- `web.eval.EvalController` → `web.api.EvalController`,`@RequestMapping("/api/v1/rule")`(前缀不变,仅换包)
- `web.sdk.*` 保留包名,前缀 `/api/v1/sdk` → `/sdk/v1`
- `web.config.dto.{CreateRuleRequest,CreateSceneRequest,UpdateSceneRequest}` → `web.admin.dto.*`
- `web.convert.SceneConvert`:更新 import 引用 `web.admin.dto.*`(MapStruct 生成类 `mvn compile` 自动重生成)
- 删除空目录:`web.config`、`web.audit`、`web.eval`

### 同步改动
- **rule-sdk**:`SnapshotPoller`(`/api/v1/sdk/snapshots` → `/sdk/v1/snapshots`)、`MetricDefinitionPoller`(路径 + Javadoc)、`MetricDefinitionPollerTest`(3 处 URL 断言)
- **docs**:`docs/10-api-contract.md` §二分组总览拆成 admin/api/sdk 三组 + 各 section 路径前缀同步

### 测试
所有 `web.config.*ControllerTest` 迁移为 `web.admin.*`,路径断言 `/api/v1/` → `/admin/v1/`;`SdkSnapshotControllerTest`/`SdkMetricDefinitionControllerTest` 路径 → `/sdk/v1/`;`EvalControllerTest` 仅换包(路径不变)。

---

## 阶段 2 — 契约规范化

### 统一分页 `PageResponse<T>`(web.common 新建)
```java
public record PageResponse<T>(List<T> items, long total, int page, int size) {
    public static <T> PageResponse<T> of(List<T> items, long total, int page, int size) { ... }
}
```
- **page 从 1 起**(对齐 MyBatis-Plus 与 RuleController 现状)。
- 转换落在 **web 层**,不改 service 签名:
  - `RuleController.listRules`:`ConfigService` 返回 `Page<T>` → web 层包成 `PageResponse`
  - `AuditController.querySessions/queryAuditLogs`:web 层接 page(从1),透传 `page-1` 给 service(service 仍 0-based),返回包成 `PageResponse`
  - `AuditController.querySessionsByRule`:web 层接 page/size,转 `offset=(page-1)*size`

### 裸 Map → record
- `web.admin.dto.CreateSceneResponse(Long id)` —— `SceneController.createScene` 返回 `ApiResponse<CreateSceneResponse>`
- `web.api.dto.PushEventResponse(String eventId, boolean accepted)` —— `EvalController.pushEvent` 返回 `ResponseEntity<ApiResponse<PushEventResponse>>`

### 类型 / 返回对齐
- `MetricController` 三个端点 `tenantId` 改 `@RequestParam String`,进 service 前 `Long.parseLong`(service/mapper 保持 Long,DB 主键为 Long)
- `RuleController.publish`:`ApiResponse<Object>` → `ApiResponse<RuleVersionSnapshot>`

### 测试 + docs
- 改 `RuleControllerTest`(`$.data.items`/`$.data.total`)、`AuditControllerTest`(page 起点+结构)、`SceneControllerTest`(`$.data.id`)、`EvalControllerTest`(`$.data.eventId/accepted`)、`MetricControllerTest`(tenantId 传 `"1"`)
- 新增 `PageResponseTest`
- `docs/10-api-contract.md`:分页响应统一 `{items,total,page,size}`;§3.1 PUSH 响应改 PushEventResponse 字段;§4.2 publish 响应改具体类型

---

## 阶段 3 — 补前端缺失读接口

### Scene 列表 `GET /admin/v1/scenes?tenantId=`
- 返回 `ApiResponse<List<SceneListItem>>`
- 新建 `config.api.dto.SceneListItem(Long id, String sceneCode, String name, String dominantMode, String subjectType, String status)`
- `SceneService` 新增 `listScenes(String tenantId)`;`SceneServiceImpl` 实现;`SceneMapper` 新增 default `findByTenantId(Long)`(LambdaQueryWrapper)

### Rule 详情 `GET /admin/v1/rules/{id}`
- 返回 `ApiResponse<RuleDetailVO>`
- 新建 `config.api.dto.RuleDetailVO(Long ruleDefinitionId, String code, String name, String status, Object conditionAst, Object decisionBindings, String sceneCode, Long currentVersionId)`
- `ConfigService` 新增 `getRuleDetail(String tenantId, Long ruleId)`,复用 `RuleDefinitionMapper.selectById` + RuleVersion 查询

### Metric 列表 `GET /admin/v1/metrics?tenantId=`
- 返回 `ApiResponse<List<MetricListItemVO>>`(web.admin.dto)
- 复用 `MetadataService.listMetricDefinitions`,`MetricController` 注入 `MetadataService`,web 层映射

### 测试 + docs
- 新增 `RuleControllerTest.getRuleDetail_*`、`SceneControllerTest.listScenes_*`、`MetricControllerTest.listMetrics_*`(各 ≥2 case)、`SceneMapperTest.findByTenantId_*`
- `docs/10-api-contract.md` §四/§五登记 3 个新接口

---

## 已定决策(2026-06-07)

1. **nodeTrace**:改契约 §3.2 为 `[]`(代码不动),与 evaluate 实际返回的空数组对齐。
2. **SDK 前缀**:`/sdk/v1/snapshots`、`/sdk/v1/metric-definitions`(去掉 /sdk 中缀)。
3. **MetadataController**:保留独立 controller。

## 实现前核实点
- `RuleVersionMapper` 取 ACTIVE 版本的 default 方法确切名称(阶段3 getRuleDetail 依赖)。
- evaluate 路径 `EvalResult.nodeTrace` 实际值(蓝图判定为 `List.of()`)。

## 文档纪律
- 改 `docs/10-api-contract.md` 前后跑 `doc-consistency-review` skill(跨章节 + 与 01-concepts 字段命名一致性)。
- 破坏性 API 变更在 `docs/README.md` §七版本史登记。

## 验证顺序
```
1. mvn -pl rule-config-svc -am test   # 阶段3 service/mapper
2. mvn -pl rule-api -am test          # 阶段1+2+3 controller
3. mvn -pl rule-sdk -am test          # 阶段1 URL
4. mvn -pl rule-app -am test          # 全模块集成冒烟
```
每阶段独立提交,提交前对应模块测试全绿。
