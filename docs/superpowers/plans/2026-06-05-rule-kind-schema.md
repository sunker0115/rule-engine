# Rule Kind Schema 修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复三个与规则 kind 相关的缺口：AstSerializer 缺少类型注册（Bug）、createDraft 不支持传入 kind、DECISION_TREE/TABLE 发布期无结构校验。

**Architecture:** 三项独立改动，均在 rule-config-svc / rule-api 两个模块内。Task 1 是 Bug 修复，Task 2 在创建草稿链路加 kind 字段，Task 3 在 publish 流程加结构断言；最后补文档。每个 task 独立可测。

**Tech Stack:** Java 21、Spring Boot、MyBatis-Plus、JUnit 5 + Mockito、Jackson 多态 Mixin

---

## 文件改动总览

| 文件 | 操作 | Task |
|---|---|---|
| `rule-config-svc/.../publish/AstSerializer.java` | 修改：补注册 `ScorecardRootNode`、`XorNode`，改为委托 `AstJsonCodec` | T1 |
| `rule-config-svc/src/test/.../publish/AstSerializerTest.java` | 修改：新增 scorecardRootNode_roundTrip、xorNode_roundTrip 两个测试 | T1 |
| `rule-api/.../config/dto/CreateRuleRequest.java` | 修改：新增 `kind` 字段（可选，默认 null） | T2 |
| `rule-api/src/test/.../config/dto/CreateRuleRequestTest.java` | 修改：更新构造参数，新增 kind 字段测试 | T2 |
| `rule-config-svc/.../publish/PublishService.java` | 修改：`createDraft` 接受 kind 参数 | T2 |
| `rule-config-svc/.../service/ConfigService.java` | 修改：`createDraft` 接口签名加 kind 参数 | T2 |
| `rule-config-svc/.../service/ConfigServiceImpl.java` | 修改：透传 kind 参数 | T2 |
| `rule-api/.../config/RuleController.java` | 修改：从 request 取 kind 传入 service | T2 |
| `rule-config-svc/src/test/.../publish/PublishServiceTest.java` | 修改：createDraft 测试补 kind 参数；新增 DECISION_TREE/TABLE 结构校验测试 | T2 + T3 |
| `rule-config-svc/src/test/.../service/ConfigServiceImplTest.java` | 修改：createDraft_delegatesToPublishService 补 kind 参数 | T2 |
| `rule-api/src/test/.../config/RuleControllerTest.java` | 修改：createDraft 相关测试补 kind 字段 | T2 |
| `rule-config-svc/.../publish/PublishService.java`（续） | 修改：`publish` 补 DECISION_TREE/TABLE 结构校验 | T3 |
| `docs/10-api-contract.md` | 修改：§4.1 CreateRuleRequest 加 kind 字段说明 | T4 |

---

## Task 1：修复 AstSerializer — 补注册 ScorecardRootNode 和 XorNode

**问题**：`AstSerializer.AstNodeMixin` 比 `AstJsonCodec.AstNodeMixin` 少两个类型，导致 SCORECARD / XOR 规则在 publish 流程中反序列化失败。
**修法**：让 `AstSerializer` 不再维护自己的 Mixin，改为持有并委托 `AstJsonCodec`（rule-kernel 模块已有），彻底消除重复。

**Files:**
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/AstSerializer.java`
- Modify: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/AstSerializerTest.java`

- [ ] **Step 1: 写两个失败测试**

在 `AstSerializerTest` 末尾追加：

```java
@Test
void scorecardRootNode_roundTrip() {
    AstNode ast = new ScorecardRootNode(
            List.of(new ConditionNode("GT", "score", null, Map.of("threshold", 60), 0.4)),
            0.6);

    String json = serializer.toJson(ast);
    AstNode restored = serializer.fromJson(json);

    assertThat(restored).isInstanceOf(ScorecardRootNode.class);
    ScorecardRootNode r = (ScorecardRootNode) restored;
    assertThat(r.conditions()).hasSize(1);
    assertThat(r.threshold()).isEqualTo(0.6);
}

@Test
void xorNode_roundTrip() {
    AstNode ast = new XorNode(
            List.of(new ConditionNode("EQ", "flag", null, Map.of("value", "A"), 0.0),
                    new ConditionNode("EQ", "flag", null, Map.of("value", "B"), 0.0)),
            "互斥条件");

    String json = serializer.toJson(ast);
    AstNode restored = serializer.fromJson(json);

    assertThat(restored).isInstanceOf(XorNode.class);
    assertThat(((XorNode) restored).children()).hasSize(2);
}
```

- [ ] **Step 2: 跑测试，确认失败**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
$MVN -pl rule-config-svc -am test -Dtest='AstSerializerTest#scorecardRootNode_roundTrip+xorNode_roundTrip' -Dsurefire.failIfNoSpecifiedTests=false
```

期望：两个测试 FAIL，报 `InvalidDefinitionException` 或 `MismatchedInputException`（找不到 ScorecardRootNode/XorNode 类型映射）。

- [ ] **Step 3: 修复 AstSerializer — 委托 AstJsonCodec**

将 `AstSerializer.java` 改为：

```java
package com.sstlfsj.rule.config.internal.publish;

import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.internal.codec.AstJsonCodec;
import org.springframework.stereotype.Component;

/**
 * 负责 AstNode 与 JSON 字符串互转，用于 rule_version.condition_ast 存储。
 * 委托 rule-kernel 的 {@link AstJsonCodec} 处理多态，保持两侧类型注册一致。
 */
@Component
public class AstSerializer {

    private final AstJsonCodec codec = new AstJsonCodec();

    /**
     * 将 AstNode 序列化为 JSON 字符串，含 type 字段便于反序列化。
     *
     * @param node 待序列化的 AST 节点
     * @return JSON 字符串
     */
    public String toJson(AstNode node) {
        try {
            return codec.createMapper().writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("AST 序列化失败", e);
        }
    }

    /**
     * 将 JSON 字符串反序列化为 AstNode（含子树递归恢复）。
     *
     * @param json 由 {@link #toJson} 生成的 JSON 字符串
     * @return 反序列化后的 AstNode
     */
    public AstNode fromJson(String json) {
        try {
            return codec.deserializeAst(json);
        } catch (Exception e) {
            throw new IllegalStateException("AST 反序列化失败: " + e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 4: 跑全量测试，确认通过**

```bash
$MVN -pl rule-config-svc -am test
```

期望：所有测试 PASS，包括原有的 `AstSerializerTest`、`PublishServiceTest` 等。

- [ ] **Step 5: Commit**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/AstSerializer.java \
        rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/AstSerializerTest.java
git commit -m "fix: AstSerializer 委托 AstJsonCodec，补全 ScorecardRootNode/XorNode 类型注册"
```

---

## Task 2：createDraft 支持传入 kind 字段

**背景**：目前 `CreateRuleRequest` 无 kind 字段，`PublishService.createDraft` 硬编码 `AST_BOOLEAN`，导致非 AST_BOOLEAN 规则创建后 kind 始终错误。

kind 字段语义：
- 前端可选传，不传时缺省 `"AST_BOOLEAN"`
- 一旦创建确定，后续不单独提供修改接口（改 kind 需重新创建草稿）
- 合法值：`AST_BOOLEAN` / `SCORECARD` / `DECISION_TREE` / `DECISION_TABLE`，非法值在 createDraft 时拒绝

**Files:**
- Modify: `rule-api/src/main/java/com/sstlfsj/rule/web/config/dto/CreateRuleRequest.java`
- Modify: `rule-api/src/test/java/com/sstlfsj/rule/web/config/dto/CreateRuleRequestTest.java`
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/service/ConfigService.java`
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/ConfigServiceImpl.java`
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PublishService.java`
- Modify: `rule-api/src/main/java/com/sstlfsj/rule/web/config/RuleController.java`
- Modify: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/PublishServiceTest.java`
- Modify: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/service/ConfigServiceImplTest.java`
- Modify: `rule-api/src/test/java/com/sstlfsj/rule/web/config/RuleControllerTest.java`

### Step 1: 写失败测试（三处）

- [ ] **Step 1a: PublishServiceTest — createDraft 传入 kind**

在 `PublishServiceTest` 的 `createDraft_insertsRuleDefinitionAndVersion` 测试中，把 `publishService.createDraft` 调用改为带 `kind` 参数，并断言 `rule_definition` 和 `rule_version` 的 kind 写入正确：

```java
// 原调用（第 201 行附近）改为：
DraftCreatedResult result = publishService.createDraft(
        1L, "risk.transfer", "rule.test", "测试规则",
        "{\"type\":\"AndNode\"}", "[]", "[]", "[]", "SCORECARD", "actor1");

// 断言 kind 被写入
ArgumentCaptor<RuleDefinition> rdCaptor = ArgumentCaptor.forClass(RuleDefinition.class);
verify(ruleDefinitionMapper).insert(rdCaptor.capture());
assertThat(rdCaptor.getValue().getKind()).isEqualTo("SCORECARD");

ArgumentCaptor<RuleVersion> rvCaptor = ArgumentCaptor.forClass(RuleVersion.class);
verify(ruleVersionMapper).insert(rvCaptor.capture());
assertThat(rvCaptor.getValue().getKind()).isEqualTo("SCORECARD");
```

同时在 `PublishServiceTest` 新增两个测试：

```java
@Test
void createDraft_invalidKind_throwsIllegalArgument() {
    SceneDef draftScene = new SceneDef();
    draftScene.setId(5L);
    draftScene.setTenantId(1L);
    draftScene.setCode("risk.transfer");
    when(sceneMapper.selectOne(any())).thenReturn(draftScene);

    assertThatThrownBy(() -> publishService.createDraft(
            1L, "risk.transfer", "rule.test", "测试规则",
            "{}", "[]", "[]", "[]", "EXPRESSION_SCRIPT", "actor1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不支持的规则 kind");
}

@Test
void createDraft_nullKind_defaultsToAstBoolean() {
    SceneDef draftScene = new SceneDef();
    draftScene.setId(5L);
    draftScene.setTenantId(1L);
    draftScene.setCode("risk.transfer");
    when(sceneMapper.selectOne(any())).thenReturn(draftScene);
    when(ruleDefinitionMapper.selectCount(any())).thenReturn(0L);

    doAnswer(inv -> { inv.getArgument(0, RuleDefinition.class).setId(10L); return 1; })
            .when(ruleDefinitionMapper).insert(any(RuleDefinition.class));
    doAnswer(inv -> { inv.getArgument(0, RuleVersion.class).setId(20L); return 1; })
            .when(ruleVersionMapper).insert(any(RuleVersion.class));
    when(auditLogMapper.insert((AuditLog) any())).thenReturn(1);

    publishService.createDraft(1L, "risk.transfer", "rule.test", "测试规则",
            "{}", "[]", "[]", "[]", null, "actor1");

    ArgumentCaptor<RuleDefinition> rdCaptor = ArgumentCaptor.forClass(RuleDefinition.class);
    verify(ruleDefinitionMapper).insert(rdCaptor.capture());
    assertThat(rdCaptor.getValue().getKind()).isEqualTo("AST_BOOLEAN");
}
```

- [ ] **Step 1b: ConfigServiceImplTest — 透传 kind**

将 `createDraft_delegatesToPublishService` 测试改为：

```java
@Test
void createDraft_delegatesToPublishService() {
    DraftCreatedResult expected = new DraftCreatedResult(1L, 2L, 1L, "DRAFT");
    when(publishService.createDraft(1L, "risk.transfer", "rule.a", "规则A",
            "{}", "[]", "[]", "[]", "AST_BOOLEAN", "actor1")).thenReturn(expected);

    DraftCreatedResult result = configService.createDraft("1", "risk.transfer",
            "rule.a", "规则A", "{}", "[]", "[]", "[]", "AST_BOOLEAN", "actor1");

    assertThat(result.ruleDefinitionId()).isEqualTo(1L);
    verify(publishService).createDraft(1L, "risk.transfer", "rule.a", "规则A",
            "{}", "[]", "[]", "[]", "AST_BOOLEAN", "actor1");
}
```

- [ ] **Step 1c: RuleControllerTest — createDraft 请求体带 kind**

将 `createDraft_returns201_withValidBody` 中的 JSON 请求体加 `"kind": "SCORECARD"` 字段，并更新 `verify(configService).createDraft(...)` 期望参数。将 `createDraft_nullJsonFields_useDefaults` 中也在 verify 参数列表补 kind（null 时传 null）。

对于 `createDraft_returns201_withValidBody`：

```java
// 请求体改为：
"""
{
  "tenantId": "t1",
  "sceneCode": "risk.transfer",
  "code": "rule.a",
  "name": "规则A",
  "kind": "SCORECARD",
  "conditionAst": {"type":"AndNode","children":[]},
  "decisionBindings": [],
  "preGates": [],
  "triggerEventTypes": []
}
"""
// verify 改为（configService.createDraft 此时签名多一个 kind 参数）：
verify(configService).createDraft(eq("t1"), eq("risk.transfer"), eq("rule.a"), eq("规则A"),
        any(), any(), any(), any(), eq("SCORECARD"), eq("user1"));
```

对于 `createDraft_nullJsonFields_useDefaults`：

```java
// 请求体不含 kind（缺省 null），verify 中 kind 传 null：
verify(configService).createDraft(eq("t1"), eq("risk.transfer"), eq("rule.a"), eq("规则A"),
        eq("{}"), eq("[]"), eq("[]"), eq("[]"), eq(null), eq("user1"));
```

- [ ] **Step 2: 跑测试，确认失败**

```bash
$MVN -pl rule-config-svc,rule-api -am test \
  -Dtest='PublishServiceTest#createDraft_insertsRuleDefinitionAndVersion+createDraft_invalidKind_throwsIllegalArgument+createDraft_nullKind_defaultsToAstBoolean,ConfigServiceImplTest#createDraft_delegatesToPublishService,RuleControllerTest#createDraft_returns201_withValidBody+createDraft_nullJsonFields_useDefaults' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

期望：编译失败（方法签名不匹配）或测试 FAIL。

- [ ] **Step 3a: 修改 CreateRuleRequest — 加 kind 字段**

```java
package com.sstlfsj.rule.web.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;

/** 创建规则草稿请求体，对应 10-api-contract.md §4.1。 */
public record CreateRuleRequest(
        @NotBlank String tenantId,
        @NotBlank String sceneCode,
        @NotBlank String code,
        @NotBlank String name,
        String kind,
        JsonNode conditionAst,
        JsonNode decisionBindings,
        JsonNode preGates,
        JsonNode triggerEventTypes
) {}
```

- [ ] **Step 3b: 修改 ConfigService 接口 — createDraft 加 kind 参数**

在 `rule-config-svc/.../api/service/ConfigService.java` 的 `createDraft` 方法签名中，在 `triggerEventTypesJson` 后加 `String kind` 参数，Javadoc 补 `@param kind 规则类型（AST_BOOLEAN / SCORECARD / DECISION_TREE / DECISION_TABLE），null 时默认 AST_BOOLEAN`。

- [ ] **Step 3c: 修改 PublishService.createDraft — 接受并写入 kind**

在 `PublishService.createDraft` 签名中，`triggerEventTypesJson` 后加 `String kind` 参数。

在方法体中，在 INSERT rule_definition 之前加校验逻辑：

```java
// kind 合法性校验（与 publish 时复用同一白名单）
String effectiveKind = (kind == null || kind.isBlank()) ? "AST_BOOLEAN" : kind;
java.util.Set<String> validKinds = java.util.Set.of(
        "AST_BOOLEAN", "SCORECARD", "DECISION_TREE", "DECISION_TABLE");
if (!validKinds.contains(effectiveKind)) {
    throw new IllegalArgumentException("不支持的规则 kind: " + effectiveKind);
}
```

将两处硬编码 `rd.setKind("AST_BOOLEAN")` 和 `rv.setKind("AST_BOOLEAN")` 改为 `effectiveKind`：

```java
rd.setKind(effectiveKind);
// ...
rv.setKind(effectiveKind);
```

- [ ] **Step 3d: 修改 ConfigServiceImpl — 透传 kind**

在 `ConfigServiceImpl.createDraft` 签名末尾加 `String kind` 参数，并在委托调用中透传：

```java
@Override
public DraftCreatedResult createDraft(String tenantId, String sceneCode,
        String code, String name,
        String conditionAstJson, String decisionBindingsJson,
        String preGatesJson, String triggerEventTypesJson,
        String kind, String actorId) {
    return publishService.createDraft(Long.valueOf(tenantId), sceneCode,
            code, name, conditionAstJson, decisionBindingsJson,
            preGatesJson, triggerEventTypesJson, kind, actorId);
}
```

- [ ] **Step 3e: 修改 RuleController — 从 request 取 kind**

将 `createDraft` 方法里的 service 调用改为：

```java
return ApiResponse.ok(configService.createDraft(
        req.tenantId(), req.sceneCode(), req.code(), req.name(),
        nodeToString(req.conditionAst(), "{}"),
        nodeToString(req.decisionBindings(), "[]"),
        nodeToString(req.preGates(), "[]"),
        nodeToString(req.triggerEventTypes(), "[]"),
        req.kind(),
        actorId));
```

- [ ] **Step 4: 更新 CreateRuleRequestTest — 补 kind 参数**

`CreateRuleRequestTest` 中所有 `new CreateRuleRequest(...)` 构造调用，在第 4 个参数（name）后插入 `null`（kind 位），保持原有测试语义不变。新增一个测试：

```java
@Test
void kind_isOptional_allowsNull() {
    var req = new CreateRuleRequest("t1", "SCENE_A", "RULE_001", "规则", null, null, null, null, null);
    Set<ConstraintViolation<CreateRuleRequest>> violations = validator.validate(req);
    assertThat(violations).isEmpty();
}
```

- [ ] **Step 5: 跑全量测试，确认通过**

```bash
$MVN -pl rule-config-svc,rule-api -am test
```

期望：所有测试 PASS。

- [ ] **Step 6: Commit**

```bash
git add \
  rule-api/src/main/java/com/sstlfsj/rule/web/config/dto/CreateRuleRequest.java \
  rule-api/src/main/java/com/sstlfsj/rule/web/config/RuleController.java \
  rule-api/src/test/java/com/sstlfsj/rule/web/config/RuleControllerTest.java \
  rule-api/src/test/java/com/sstlfsj/rule/web/config/dto/CreateRuleRequestTest.java \
  rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/service/ConfigService.java \
  rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/ConfigServiceImpl.java \
  rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PublishService.java \
  rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/PublishServiceTest.java \
  rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/service/ConfigServiceImplTest.java
git commit -m "feat: createDraft 支持传入 kind，创建时校验合法性，缺省 AST_BOOLEAN"
```

---

## Task 3：DECISION_TREE / DECISION_TABLE 发布期结构校验

**目标**：在 `PublishService.publish` 中，对 DECISION_TREE / DECISION_TABLE kind 补充与 SCORECARD 平行的轻量结构校验。

校验规则：
- **DECISION_TREE**：根节点必须是 `IfNode`；`IfNode.thenBranch()` 不得为 null（避免评估器 NPE）
- **DECISION_TABLE**：根节点必须是 `DecisionTableNode`；`columns` 非空；`rows` 非空；每行 `conditions.size() == columns.size()`

**Files:**
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PublishService.java`
- Modify: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/PublishServiceTest.java`

- [ ] **Step 1: 写失败测试**

在 `PublishServiceTest` 新增四个测试（放在现有 SCORECARD 测试之后）：

```java
@Test
void publish_decisionTree_非IfNode根节点_抛异常() {
    draftRule.setKind("DECISION_TREE");
    when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
    when(sceneMapper.selectById(5L)).thenReturn(scene);
    when(ruleVersionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(draftVersion);
    // 根节点是 ConditionNode，不是 IfNode
    when(astSerializer.fromJson(anyString()))
            .thenReturn(new ConditionNode("GT", "amount", null, Map.of(), 0.0));

    assertThatThrownBy(() -> publishService.publish(1L, 10L, "op"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("IfNode");
}

@Test
void publish_decisionTree_thenBranchNull_抛异常() {
    draftRule.setKind("DECISION_TREE");
    when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
    when(sceneMapper.selectById(5L)).thenReturn(scene);
    when(ruleVersionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(draftVersion);
    // thenBranch = null
    IfNode badTree = new IfNode(
            new ConditionNode("GT", "amount", null, Map.of(), 0.0),
            null, null);
    when(astSerializer.fromJson(anyString())).thenReturn(badTree);

    assertThatThrownBy(() -> publishService.publish(1L, 10L, "op"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("thenBranch");
}

@Test
void publish_decisionTable_非DecisionTableNode根节点_抛异常() {
    draftRule.setKind("DECISION_TABLE");
    when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
    when(sceneMapper.selectById(5L)).thenReturn(scene);
    when(ruleVersionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(draftVersion);
    when(astSerializer.fromJson(anyString()))
            .thenReturn(new ConditionNode("GT", "amount", null, Map.of(), 0.0));

    assertThatThrownBy(() -> publishService.publish(1L, 10L, "op"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("DecisionTableNode");
}

@Test
void publish_decisionTable_行列数不一致_抛异常() {
    draftRule.setKind("DECISION_TABLE");
    when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
    when(sceneMapper.selectById(5L)).thenReturn(scene);
    when(ruleVersionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(draftVersion);
    // 2 列但行只有 1 个条件值
    DecisionTableNode table = new DecisionTableNode(
            List.of(new DecisionTableNode.Column("amount", "GT"),
                    new DecisionTableNode.Column("count", "LT")),
            List.of(new DecisionTableNode.Row(List.of(1000), "BLOCK")));
    when(astSerializer.fromJson(anyString())).thenReturn(table);

    assertThatThrownBy(() -> publishService.publish(1L, 10L, "op"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("列数");
}
```

同时，把现有测试 `publish_decisionTreeKind_正常通过` 中 `astSerializer.fromJson` 的返回值换成合法 `IfNode`（当前返回的是 `ConditionNode`，加了校验后会失败）：

```java
// 原来：
when(astSerializer.fromJson(any()))
        .thenReturn(new ConditionNode("EQ", "m1", null, Map.of(), 0.0));
// 改为：
when(astSerializer.fromJson(any()))
        .thenReturn(new IfNode(
                new ConditionNode("GT", "amount", null, Map.of(), 0.0),
                new DecisionLeafNode("BLOCK", "HIGH_RISK"),
                new DecisionLeafNode("PASS", "LOW_RISK")));
```

类似地，把 `publish_decisionTableKind_正常通过` 中的 mock 换成合法 `DecisionTableNode`：

```java
when(astSerializer.fromJson(any()))
        .thenReturn(new DecisionTableNode(
                List.of(new DecisionTableNode.Column("amount", "GT")),
                List.of(new DecisionTableNode.Row(List.of(1000), "BLOCK"))));
```

- [ ] **Step 2: 跑测试，确认失败**

```bash
$MVN -pl rule-config-svc -am test \
  -Dtest='PublishServiceTest#publish_decisionTree_非IfNode根节点_抛异常+publish_decisionTree_thenBranchNull_抛异常+publish_decisionTable_非DecisionTableNode根节点_抛异常+publish_decisionTable_行列数不一致_抛异常' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

期望：4 个新测试 FAIL（无 kind 校验，不抛出异常）。

- [ ] **Step 3: 在 PublishService.publish 中加校验**

在现有 SCORECARD 校验块（`if ("SCORECARD".equals(kind)) { ... }`）之后紧接着加：

```java
// DECISION_TREE 校验：根节点必须是 IfNode，thenBranch 不得为 null
if ("DECISION_TREE".equals(kind)) {
    if (!(ast instanceof IfNode ifRoot)) {
        throw new IllegalArgumentException(
                "kind=DECISION_TREE 的规则 conditionAst 根节点必须是 IfNode");
    }
    if (ifRoot.thenBranch() == null) {
        throw new IllegalArgumentException(
                "kind=DECISION_TREE 的 IfNode thenBranch 不得为 null");
    }
}
// DECISION_TABLE 校验：根节点必须是 DecisionTableNode，columns/rows 非空，行列数一致
if ("DECISION_TABLE".equals(kind)) {
    if (!(ast instanceof DecisionTableNode tableRoot)) {
        throw new IllegalArgumentException(
                "kind=DECISION_TABLE 的规则 conditionAst 根节点必须是 DecisionTableNode");
    }
    if (tableRoot.columns() == null || tableRoot.columns().isEmpty()) {
        throw new IllegalArgumentException("DECISION_TABLE columns 不得为空");
    }
    if (tableRoot.rows() == null || tableRoot.rows().isEmpty()) {
        throw new IllegalArgumentException("DECISION_TABLE rows 不得为空");
    }
    int colCount = tableRoot.columns().size();
    for (int i = 0; i < tableRoot.rows().size(); i++) {
        DecisionTableNode.Row row = tableRoot.rows().get(i);
        if (row.conditions().size() != colCount) {
            throw new IllegalArgumentException(
                    "DECISION_TABLE 第 " + i + " 行 conditions 数量（" + row.conditions().size()
                    + "）与列数（" + colCount + "）不一致");
        }
    }
}
```

- [ ] **Step 4: 跑全量测试，确认通过**

```bash
$MVN -pl rule-config-svc -am test
```

期望：所有测试 PASS。

- [ ] **Step 5: Commit**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PublishService.java \
        rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/PublishServiceTest.java
git commit -m "feat: publish 时对 DECISION_TREE/TABLE 做轻量结构校验"
```

---

## Task 4：更新文档 10-api-contract.md

**Files:**
- Modify: `docs/10-api-contract.md`

- [ ] **Step 1: 在 §4.1 CreateRuleRequest 请求体描述中加 kind 字段**

找到 §4.1（或 POST /api/v1/rules 的请求体说明），在现有字段列表中加入：

```
| kind            | String | 否   | 规则类型，默认 AST_BOOLEAN；可选值：AST_BOOLEAN / SCORECARD / DECISION_TREE / DECISION_TABLE |
```

如果当前文档该处以 JSON 示例展示，则在示例中加入 `"kind": "AST_BOOLEAN"` 一行（注释或可选字段）。

- [ ] **Step 2: Commit**

```bash
git add docs/10-api-contract.md
git commit -m "docs: CreateRuleRequest 补 kind 字段说明"
```

---

## 验证命令汇总

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn

# Task 1
$MVN -pl rule-config-svc -am test

# Task 2 + Task 3
$MVN -pl rule-config-svc,rule-api -am test
```
