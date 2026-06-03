# createDraft 实装计划（POST /api/v1/rules）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实装 `POST /api/v1/rules` 创建规则草稿端点，替换 501 占位实现。

**Architecture:** `RuleController` 接收请求 → `ConfigServiceImpl.createDraft` 委托 → `PublishService.createDraft` 事务内插入 `rule_definition`（DRAFT）+ `rule_version`（DRAFT）+ `audit_log`，返回 201 + `DraftCreatedResult`。

**Tech Stack:** Java 25 / Spring Boot 4 / MyBatis-Plus 3.5.16 / Jackson `JsonNode` for AST fields

---

## 改动文件清单

| 文件 | 动作 |
|---|---|
| `rule-config-svc/.../api/dto/DraftCreatedResult.java` | 新建响应 DTO |
| `rule-api/.../dto/CreateRuleRequest.java` | 改字段：`sceneId→sceneCode` + 加 4 个 `JsonNode` 字段 |
| `rule-config-svc/.../api/service/ConfigService.java` | 加 `createDraft(...)` 方法 |
| `rule-config-svc/.../internal/publish/PublishService.java` | 加 `createDraft(...)` 方法（含 code 唯一性校验） |
| `rule-config-svc/.../internal/service/ConfigServiceImpl.java` | 实现 `createDraft`，委托 PublishService |
| `rule-api/.../web/config/RuleController.java` | 替换 501 占位为真实实现，`nodeToString` 用 `JsonNode.toString()` |

---

### Task 1: DraftCreatedResult + CreateRuleRequest 更新 + ConfigService 接口

**Files:**
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/DraftCreatedResult.java`
- Create: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/api/dto/DraftCreatedResultTest.java`
- Modify: `rule-api/src/main/java/com/sstlfsj/rule/web/config/dto/CreateRuleRequest.java`
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/service/ConfigService.java`

- [x] **Step 1: 新建 DraftCreatedResult record**

```java
package com.sstlfsj.rule.config.api.dto;

/**
 * 创建规则草稿响应，对应 10-api-contract.md §4.1 Response 201。
 * @param ruleDefinitionId 新建的规则定义 ID
 * @param ruleVersionId    新建的规则版本 ID
 * @param version          版本号（草稿固定为 1）
 * @param status           状态（固定为 DRAFT）
 */
public record DraftCreatedResult(
        Long ruleDefinitionId, Long ruleVersionId, Long version, String status
) {}
```

- [x] **Step 2: 更新 CreateRuleRequest**

`sceneId(Long, @NotNull)` → `sceneCode(String, @NotBlank)`，新增 4 个可选 `JsonNode` 字段（`conditionAst`, `decisionBindings`, `preGates`, `triggerEventTypes`）。

- [x] **Step 3: ConfigService 加 createDraft 方法签名**

参数：`String tenantId, String sceneCode, String code, String name, String conditionAstJson, String decisionBindingsJson, String preGatesJson, String triggerEventTypesJson, String actorId`，返回 `DraftCreatedResult`。

- [x] **Step 4: 跑测试**

```bash
$MVN -pl rule-config-svc -am test -Dtest='DraftCreatedResultTest' -Dsurefire.failIfNoSpecifiedTests=false
```

- [x] **Step 5: Commit**

```bash
git commit -m "feat(config): DraftCreatedResult DTO + ConfigService.createDraft 接口 + CreateRuleRequest 字段更新"
```

---

### Task 2: PublishService.createDraft + ConfigServiceImpl 实现

**Files:**
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PublishService.java`
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/ConfigServiceImpl.java`
- Modify: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/PublishServiceTest.java`
- Modify: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/service/ConfigServiceImplTest.java`

- [x] **Step 1: PublishService.createDraft 事务实现**

流程（`@Transactional`）：
1. 按 `tenantId + sceneCode` 查 `SceneDef`，不存在抛 `IllegalArgumentException`
2. 校验 `code` 在同 `tenant+scene` 下唯一（`ruleDefinitionMapper.selectCount`），重复抛 `IllegalArgumentException`
3. INSERT `rule_definition`（status=DRAFT, kind=AST_BOOLEAN, createdBy=actorId）
4. INSERT `rule_version`（version=1, status=DRAFT，各 JSON 字段空时用默认值 `"{}"` / `"[]"`）
5. INSERT `audit_log`（action=CREATE）
6. 返回 `DraftCreatedResult`

参考 `publish()` 方法的事务边界和 audit 模式。

- [x] **Step 2: ConfigServiceImpl 委托实现**

```java
return publishService.createDraft(Long.valueOf(tenantId), sceneCode, ...);
```

与 `publish()` 委托模式一致（`ConfigService` 接口用 `String tenantId`，内部 `PublishService` 用 `Long tenantId`）。

- [x] **Step 3: 补 PublishServiceTest 两个用例**

- `createDraft_insertsRuleDefinitionAndVersion()`：ArgumentCaptor 验证 INSERT 字段
- `createDraft_sceneNotFound_throwsIllegalArgument()`
- `createDraft_duplicateCode_throwsIllegalArgument()`

- [x] **Step 4: 跑测试**

```bash
$MVN -pl rule-config-svc -am test
```

- [x] **Step 5: Commit**

```bash
git commit -m "feat(config): PublishService.createDraft + ConfigServiceImpl 委托实现"
```

---

### Task 3: RuleController 真实实现 + 全量测试

**Files:**
- Modify: `rule-api/src/main/java/com/sstlfsj/rule/web/config/RuleController.java`
- Modify: `rule-api/src/test/java/com/sstlfsj/rule/web/config/RuleControllerTest.java`

- [x] **Step 1: 替换 createDraft 501 占位**

```java
@PostMapping
@ResponseStatus(HttpStatus.CREATED)
public ApiResponse<DraftCreatedResult> createDraft(
        @Valid @RequestBody CreateRuleRequest req,
        @RequestHeader("X-Actor-Id") String actorId) {
    return ApiResponse.ok(configService.createDraft(
            req.tenantId(), req.sceneCode(), req.code(), req.name(),
            nodeToString(req.conditionAst(), "{}"),
            nodeToString(req.decisionBindings(), "[]"),
            nodeToString(req.preGates(), "[]"),
            nodeToString(req.triggerEventTypes(), "[]"),
            actorId));
}

private static String nodeToString(JsonNode node, String defaultVal) {
    if (node == null || node.isNull()) return defaultVal;
    return node.toString();  // Jackson JsonNode.toString() 直接输出紧凑 JSON，不需要 ObjectMapper
}
```

- [x] **Step 2: 更新 RuleControllerTest**

删除 501 测试，新增：
- `createDraft_returns201_withValidBody()`：201 + `$.data.ruleDefinitionId`
- `createDraft_returns400_whenTenantIdMissing()`：400
- `createDraft_nullJsonFields_useDefaults()`：null JsonNode 时传默认值字符串

- [x] **Step 3: 跑全量测试**

```bash
$MVN -pl rule-config-svc,rule-api -am test
```

- [x] **Step 4: Commit**

```bash
git commit -m "feat(api): RuleController createDraft 替换 501 占位为真实实现"
```

---

## 实装备注（已完成）

- `nodeToString` 最终用 `JsonNode.toString()` 而非 `new ObjectMapper()`，避免与 Spring Boot 4 自动配置的 Jackson 3.x 实例不一致
- `PublishService.createDraft` 在 INSERT 前加 code 唯一性前置检查（DB 有 `UNIQUE KEY uk_tenant_code`，前置检查给出友好错误信息）
- 39 个测试全部通过，最终提交 `e21f017`
