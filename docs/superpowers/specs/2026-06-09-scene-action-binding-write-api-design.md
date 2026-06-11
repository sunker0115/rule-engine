# Scene Action Binding 写 API 设计

> 日期：2026-06-09。状态：已实现（含下述修订）。源自 SceneActionBindingIndex 失效缺口：`scene_action_binding` 当前 seed-only（无写 API），index 仅靠启动全量 + `SceneChangedEvent` 刷新，binding 改动在进程重启前不生效。本设计补 binding 写 API（config-svc）+ 写后发 `SceneChangedEvent`（active=场景真实状态）闭合失效。

> **实现修订（D50）**：① **移除 `rate_limit_override`**（V1_14 DROP COLUMN）——action 级频控无消费方且冗余，本文下方涉及该字段处作废。② **JSON 字段一律 `Map<String,Object>`**，不用 JSON String、不用裸 `Object`：`SceneActionBindingItem.defaultParams` / `ActionBindingItemDto.defaultParams` 均为 `Map<String,Object>`；JSON↔串序列化下沉 service 实现（注入 ObjectMapper），controller 不做转换。③ **DTO ↔ service 项转换走 MapStruct**（`web/admin/convert/SceneActionBindingConvert`），不在 controller 内联。④ 顺带**接入 `default_params` 到派发**：`SceneActionBindingIndex` 装载时解析 `default_params`→Map 缓存，`ActionDispatchService` 传入 `ActionContext.params`。

## 1. 背景与定位
- `scene_action_binding` = Scene 可用 actionType 白名单（含 Scene 级 `default_params` / `rate_limit_override`，仅 PUSH/HYBRID），DDL 见 `V1_0`，uk = `(scene_id, action_type)`。
- eval 侧 `SceneActionBindingIndex`：启动 `findAll` 全量 + 监听 `SceneChangedEvent` 按场景刷新（`active=true` → `findBySceneCode` 重载；`active=false` → 移除）。
- 缺口：全仓**无任何写 `scene_action_binding` 的代码**，且 `SceneChangedEvent` 唯一发布点是 `SceneServiceImpl.disableScene()`（active=false）。binding 改动无事件 → 索引重启前不刷新。
- docs/01-concepts §168（D24）已明确设计本意：**「Scene 配置（bindings/payloadSchema/status）变更 → SceneChangedEvent 触发热加载」**。本设计即「设计了没接线」的补全。

## 2. 范围
- **只做 action binding**（它有 eval 热索引、是缺口所在）；metric binding（`scene_metric_binding`，无热索引、白名单仅发布期校验用）不在本期。
- **不做 actionType-handler 存在性校验**：v1 SPI registry 返回空（`MetadataServiceImpl` 同口径），binding 即白名单本身，handler 缺失留到 dispatch 期暴露。

## 3. API（面向前端白名单编辑器）
- `GET /admin/v1/scenes/{sceneCode}/action-bindings?tenantId=` → 列当前绑定 `[{actionType, defaultParams, rateLimitOverride}]`，渲染编辑器。
- `PUT /admin/v1/scenes/{sceneCode}/action-bindings?tenantId=` → **整组覆盖式保存**。body `{ "bindings": [{actionType, defaultParams?, rateLimitOverride?}] }`，actor 取 `X-Actor-Id` header。
  - **为什么整组覆盖而非逐项 upsert/delete**：前端编辑器「保存」一次提交全量集合，服务端单事务 reconcile（删 payload 缺失项、upsert payload 内项），**单次原子 + 单事件**，客户端无需做增量 diff —— 对白名单这种小集合的编辑器最实用。

## 4. 组件
1. **config-svc**
   - `domain/SceneActionBindingDef`（`@TableName("scene_action_binding")`，MyBatis-Plus，字段 id/sceneId/actionType/defaultParams/rateLimitOverride/审计列）。
   - `repository/SceneActionBindingMapper extends BaseMapper<SceneActionBindingDef>`（`@Mapper` + `default` 方法：`findBySceneId`、`deleteBySceneIdAndActionType`）。
   - `api/service/SceneActionBindingService` 接口 + DTO `record SceneActionBindingItem(String actionType, String defaultParamsJson, String rateLimitOverrideJson)`：
     - `List<SceneActionBindingItem> list(String tenantId, String sceneCode)`
     - `void replace(String tenantId, String sceneCode, List<SceneActionBindingItem> items, String actorId)`
   - `internal/service/SceneActionBindingServiceImpl`（`@Service @RequiredArgsConstructor`，`replace` 标 `@Transactional`）：
     - `SceneMapper.findByCode` 取 scene（不存在抛 `IllegalArgumentException`，沿用 SceneServiceImpl 口径）。
     - payload 内 `actionType` 重复 → 抛 `IllegalArgumentException`（前端不该送重复）。
     - reconcile：现有集合 vs payload，删多余、insert 新增、update 已存在（按 uk）。
     - `writeAudit`（action `REPLACE_ACTION_BINDING`，targetType `scene_action_binding`，targetId=sceneId）。
     - 末尾 `publishEvent(new SceneChangedEvent(tenantId, sceneCode, scene.getStatus().equals("ACTIVE")))`。
2. **rule-api**
   - `web/admin/dto/ActionBindingItemDto(String actionType, Object defaultParams, Object rateLimitOverride)`、`ReplaceActionBindingsRequest(String tenantId, List<ActionBindingItemDto> bindings)`。
   - `web/admin/SceneActionBindingController`（`@RestController`，`/admin/v1/scenes/{sceneCode}/action-bindings`）：GET 调 `list`；PUT 用注入 `ObjectMapper` 把 `defaultParams`/`rateLimitOverride` Object→JSON 串后调 `replace`，actor 取 `@RequestHeader("X-Actor-Id")`。返回 `ApiResponse`。
3. **eval-svc**：**无改动**。`SceneActionBindingIndex.onSceneChanged(active=true)` 已会 `findBySceneCode` 重载该场景 binding——缺口纯靠 config-svc 发事件闭合。

## 5. 失效闭合
- `replace` 末尾发 `SceneChangedEvent(tenantId, sceneCode, active = scene.status==ACTIVE)`：
  - 场景 ACTIVE → `active=true` → `SceneActionBindingIndex` 重载（反映增/删/改）；`SceneIndexEventListener` 同时重载规则索引（白重载、binding 编辑极少、可接受）。
  - 场景 DISABLED → `active=false` → 索引移除（本就不在，no-op），**不复活**已禁用场景。这是 active 取真实状态、不写死 true 的原因。

## 6. 错误处理
- scene 不存在 → `IllegalArgumentException`。
- payload 内 actionType 重复 → `IllegalArgumentException`。
- `defaultParams`/`rateLimitOverride` 为任意 JSON 对象，允许 null（存 null）。

## 7. 测试
- `SceneActionBindingServiceImplTest`（mock SceneMapper/BindingMapper/AuditLogMapper/ApplicationEventPublisher）：
  - replace 新增/更新/删除 reconcile 正确；
  - 发 `SceneChangedEvent` 且 `active` 取场景状态（ACTIVE→true、DISABLED→false）；
  - scene 不存在抛异常；payload 重复 actionType 抛异常；
  - list 返回映射正确。
- `SceneActionBindingControllerTest`：Object→JSON 透传 + 调 service（直调或 MockMvc）。
- 回归：eval 侧 `SceneActionBindingIndexTest` 已覆盖 `onSceneChanged(active=true)` 重载，不动。

## 8. native / 风险
- 纯 Spring/MyBatis CRUD + 事件发布，无新反射 / 无 preview，GraalVM native 零新增风险。
- 整组覆盖式保存为 last-write-wins（并发两编辑器互覆盖）；单人 admin 工具可接受，非目标做乐观锁。

## 9. 非目标
- metric binding 写 API（另期）。
- actionType-handler 存在性校验（v1 registry 空，留 dispatch 期）。
- 逐项 upsert/delete 端点（整组覆盖已覆盖编辑器全部场景）。
- binding 写的乐观锁 / 并发控制。
