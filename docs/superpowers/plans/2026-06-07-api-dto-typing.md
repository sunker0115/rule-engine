# API DTO 类型化 Implementation Plan（一期）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `CreateRuleRequest` / `RuleDetailVO` / `JobDefinitionDto` 的弱类型 `Object` / `String` JSON 字段还原为 typed 对象（`AstNode` / `List<DecisionBindingInput>` / `List<PreGateConfig>` / `List<String>` / sealed `SubjectQuery`），让入口校验和 Swagger 契约恢复类型，线上 JSON 形状不变。

**Architecture:** 纯 API DTO 层重构。复用 kernel 已有 typed 模型（`AstNode` 多态、`PreGateConfig`、`DecisionBinding`）与现成序列化（控制器 `nodeToString` / 服务 `objectMapper`）。持久化实体仍 String（二期再收）。新增失败模式——Jackson typed 绑定失败——映射成 400。动态 by design 字段（payload / providedMetrics / *.params / defaultParams）保持 Map，不动。

**Tech Stack:** Java 25 / Spring Boot 4 / Jackson 3（databind 包名 `tools.jackson.*`，注解包名仍 `com.fasterxml.jackson.annotation.*`）/ MyBatis-Plus / JUnit5 + Mockito + MockMvc + Testcontainers / AssertJ。

**设计依据:** `docs/superpowers/specs/2026-06-07-api-dto-typing-design.md`

---

## 环境（每次跑测试前）

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
```

> 关键不变量：**字段名与语义不变**。只换 Java 绑定类型，每个 typed record 的字段名必须与原 JSON key 完全一致。
>
> **一个已知且可接受的形状差异**：typed `AstNode` 序列化会**显式输出可选字段的 null**（如 `"displayLabel":null,"weight":null`），故 `conditionAst` 的存储/读回 JSON 会从最小形态 `{"type":"AndNode","children":[]}` 变成完整形态 `{"type":"AndNode","children":[],"displayLabel":null,"weight":null}`。这与**引擎 canonical 存储形态一致**（rule_version 集成测 seed 本就是完整形态），且对 JSON 客户端是向后兼容的（多出的 null key 可忽略）。契约文档的最小示例仍是合法**输入**。因此 AST 相关断言**不要断言字节级相等**，只断言结构/类型；`DecisionBindingInput`（单字段无 null）与 `triggerEventTypes`（String 列表）则字节级一致。

---

## File Structure

| 文件 | 责任 | 动作 |
|---|---|---|
| `rule-job-svc/.../job/api/SubjectQuery.java` | Job 主体查询判别联合（sealed + Jackson 多态） | 创建 |
| `rule-job-svc/.../job/api/BeanMethodQuery.java` | `SubjectQuery` 的 BEAN_METHOD 实现 | 创建 |
| `rule-job-svc/.../job/internal/subject/BeanMethodSubjectQueryRunner.java` | 运行时按 typed `SubjectQuery` 解析 | 修改 |
| `rule-job-svc/.../job/api/dto/JobDefinitionDto.java` | `subjectQuery` 改 typed、删 `payloadTemplate` | 修改 |
| `rule-job-svc/.../job/internal/service/JobServiceImpl.java` | `toDto` 解析 subjectQuery、去 payloadTemplate | 修改 |
| `rule-api/.../web/common/GlobalExceptionHandler.java` | 绑定失败 → 400 | 修改 |
| `rule-app/src/main/resources/application.yml` | Jackson 宽松未知字段（与 kernel codec 一致） | 修改 |
| `rule-api/.../web/admin/dto/DecisionBindingInput.java` | 创建规则的决策绑定入参（仅 decisionCode） | 创建 |
| `rule-api/.../web/admin/dto/CreateRuleRequest.java` | 4 个 `Object` 字段改 typed | 修改 |
| `rule-config-svc/.../config/api/dto/RuleDetailVO.java` | `conditionAst` / `decisionBindings` 改 typed | 修改 |
| `rule-config-svc/.../config/internal/service/ConfigServiceImpl.java` | `getRuleDetail` 改 typed 反序列化 | 修改 |

---

### Task 1: SubjectQuery 判别联合（sealed + Jackson 多态）

**Files:**
- Create: `rule-job-svc/src/main/java/com/sstlfsj/rule/job/api/SubjectQuery.java`
- Create: `rule-job-svc/src/main/java/com/sstlfsj/rule/job/api/BeanMethodQuery.java`
- Test: `rule-job-svc/src/test/java/com/sstlfsj/rule/job/api/SubjectQueryTest.java`

- [ ] **Step 1: 写失败测试**

`rule-job-svc/src/test/java/com/sstlfsj/rule/job/api/SubjectQueryTest.java`:

```java
package com.sstlfsj.rule.job.api;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class SubjectQueryTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void beanMethodQuery_roundTripWithTypeField() {
        SubjectQuery q = new BeanMethodQuery("demoFraudJob#recentLoginUsers");
        String json = mapper.writeValueAsString(q);
        // 序列化必须带 type 判别字段，且形状与库中存的一致
        assertThat(json).contains("\"type\":\"BEAN_METHOD\"");
        assertThat(json).contains("\"ref\":\"demoFraudJob#recentLoginUsers\"");

        SubjectQuery back = mapper.readValue(json, SubjectQuery.class);
        assertThat(back).isInstanceOf(BeanMethodQuery.class);
        assertThat(((BeanMethodQuery) back).ref()).isEqualTo("demoFraudJob#recentLoginUsers");
    }

    @Test
    void deserializeFromStoredJson() {
        SubjectQuery back = mapper.readValue(
                "{\"type\":\"BEAN_METHOD\",\"ref\":\"a#b\"}", SubjectQuery.class);
        assertThat(((BeanMethodQuery) back).ref()).isEqualTo("a#b");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
$MVN -pl rule-job-svc -am test -Dtest='SubjectQueryTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译失败（`SubjectQuery` / `BeanMethodQuery` 不存在）。

- [ ] **Step 3: 写 SubjectQuery + BeanMethodQuery**

`rule-job-svc/src/main/java/com/sstlfsj/rule/job/api/SubjectQuery.java`:

```java
package com.sstlfsj.rule.job.api;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Job 主体查询配置的判别联合（存于 job_definition.subject_query JSON）。
 * 多态注解打在接口上，与 AstNode 同风格，全局 ObjectMapper 与 codec 均可解析。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(@JsonSubTypes.Type(value = BeanMethodQuery.class, name = "BEAN_METHOD"))
public sealed interface SubjectQuery permits BeanMethodQuery {}
```

`rule-job-svc/src/main/java/com/sstlfsj/rule/job/api/BeanMethodQuery.java`:

```java
package com.sstlfsj.rule.job.api;

/**
 * BEAN_METHOD 型主体查询：反射调用 {@code ref}（{@code <bean>#<method>}）指向的 @RuleJob 方法。
 *
 * @param ref Spring bean 名 + 方法名，格式 {@code <bean>#<method>}
 */
public record BeanMethodQuery(String ref) implements SubjectQuery {}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
$MVN -pl rule-job-svc -am test -Dtest='SubjectQueryTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add rule-job-svc/src/main/java/com/sstlfsj/rule/job/api/SubjectQuery.java \
  rule-job-svc/src/main/java/com/sstlfsj/rule/job/api/BeanMethodQuery.java \
  rule-job-svc/src/test/java/com/sstlfsj/rule/job/api/SubjectQueryTest.java
git commit -m "feat(job): SubjectQuery sealed 判别联合（BEAN_METHOD）"
```

---

### Task 2: BeanMethodSubjectQueryRunner 用 typed SubjectQuery 解析

**Files:**
- Modify: `rule-job-svc/src/main/java/com/sstlfsj/rule/job/internal/subject/BeanMethodSubjectQueryRunner.java`
- Test: `rule-job-svc/src/test/java/com/sstlfsj/rule/job/internal/subject/BeanMethodSubjectQueryRunnerTest.java`

当前实现把 JSON 解析成 `Map`、手检 `type=="BEAN_METHOD"`、取 `ref`。改成 `readValue(json, SubjectQuery.class)` + sealed `switch`。

- [ ] **Step 1: 改测试（先让既有测试反映新解析）**

把 `BeanMethodSubjectQueryRunnerTest` 的非法 type 用例断言保留（仍应抛 `IllegalArgumentException` 或反序列化异常），并确保合法用例走 typed 路径。替换整个文件：

```java
package com.sstlfsj.rule.job.internal.subject;

import com.sstlfsj.rule.job.api.JobTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BeanMethodSubjectQueryRunnerTest {

    @Mock
    BeanMethodRegistry registry;

    BeanMethodSubjectQueryRunner runner;

    @BeforeEach
    void setUp() {
        runner = new BeanMethodSubjectQueryRunner(registry, JsonMapper.builder().build());
    }

    @Test
    void invokesRegisteredMethodAndReturnsTargets() {
        when(registry.invoke("a#b")).thenReturn(
                List.of(JobTarget.of("u1"), JobTarget.of("u2")));
        List<JobTarget> targets = runner.query("{\"type\":\"BEAN_METHOD\",\"ref\":\"a#b\"}");
        assertThat(targets).hasSize(2);
        assertThat(targets.get(0).subjectId()).isEqualTo("u1");
    }

    @Test
    void rejectsUnknownType() {
        assertThatThrownBy(() -> runner.query("{\"type\":\"SQL\"}"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void rejectsBlankConfig() {
        assertThatThrownBy(() -> runner.query(""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
$MVN -pl rule-job-svc -am test -Dtest='BeanMethodSubjectQueryRunnerTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 现有实现可能仍过/部分过；目标是改实现后全过。先看红点（如 unknown type 现在抛的异常类型不符）。

- [ ] **Step 3: 改实现**

替换 `BeanMethodSubjectQueryRunner.java` 的 import 与 `query` 方法：

```java
package com.sstlfsj.rule.job.internal.subject;

import com.sstlfsj.rule.job.api.BeanMethodQuery;
import com.sstlfsj.rule.job.api.JobTarget;
import com.sstlfsj.rule.job.api.SubjectQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 主体查询实现：解析 subjectQuery 配置为 typed {@link SubjectQuery}，按子类型反射调用
 * {@code @RuleJob} 注解的业务查询方法取目标（{@link JobTarget} 列表）。
 *
 * <p>首期仅 BEAN_METHOD；新增子类型只需扩 {@code SubjectQuery} permits + 下方 switch 分支。
 */
@Component
@RequiredArgsConstructor
class BeanMethodSubjectQueryRunner implements SubjectQueryRunner {

    private final BeanMethodRegistry registry;
    private final ObjectMapper objectMapper;

    @Override
    public List<JobTarget> query(String subjectQueryJson) {
        if (subjectQueryJson == null || subjectQueryJson.isBlank()) {
            throw new IllegalArgumentException("subjectQuery 配置不能为空");
        }
        SubjectQuery query = objectMapper.readValue(subjectQueryJson, SubjectQuery.class);
        return switch (query) {
            case BeanMethodQuery b -> registry.invoke(b.ref());
        };
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
$MVN -pl rule-job-svc -am test -Dtest='BeanMethodSubjectQueryRunnerTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add rule-job-svc/src/main/java/com/sstlfsj/rule/job/internal/subject/BeanMethodSubjectQueryRunner.java \
  rule-job-svc/src/test/java/com/sstlfsj/rule/job/internal/subject/BeanMethodSubjectQueryRunnerTest.java
git commit -m "refactor(job): BeanMethodSubjectQueryRunner 用 typed SubjectQuery 解析"
```

---

### Task 3: JobDefinitionDto 改 typed subjectQuery + 删 payloadTemplate

**Files:**
- Modify: `rule-job-svc/src/main/java/com/sstlfsj/rule/job/api/dto/JobDefinitionDto.java`
- Modify: `rule-job-svc/src/main/java/com/sstlfsj/rule/job/internal/service/JobServiceImpl.java:94-106`（`toDto`）
- Test: `rule-job-svc/src/test/java/com/sstlfsj/rule/job/internal/service/JobServiceImplTest.java`、`rule-api/src/test/java/com/sstlfsj/rule/web/admin/JobControllerTest.java`

- [ ] **Step 1: 改 JobServiceImplTest（断言 typed subjectQuery、无 payloadTemplate）**

在 `JobServiceImplTest` 里找到断言 `JobDefinitionDto` 的用例，把对 `subjectQuery()` 的断言改为 typed。新增/调整一条：

```java
// 在 JobServiceImplTest 中，getJob/list 返回 dto 的断言处：
// 假设 mock 的 JobDefinition.subjectQuery = "{\"type\":\"BEAN_METHOD\",\"ref\":\"a#b\"}"
com.sstlfsj.rule.job.api.dto.JobDefinitionDto dto = /* 调用 service 得到的 dto */;
assertThat(dto.subjectQuery()).isInstanceOf(com.sstlfsj.rule.job.api.BeanMethodQuery.class);
assertThat(((com.sstlfsj.rule.job.api.BeanMethodQuery) dto.subjectQuery()).ref()).isEqualTo("a#b");
```

> 若该测试当前未对 subjectQuery 断言，仅需保证 mock 的 `JobDefinition.subjectQuery` 是合法 JSON（`{"type":"BEAN_METHOD","ref":"a#b"}`），并删除任何对 `payloadTemplate()` 的引用。

- [ ] **Step 2: 跑测试确认失败**

```bash
$MVN -pl rule-job-svc -am test -Dtest='JobServiceImplTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译失败（`JobDefinitionDto.subjectQuery()` 仍是 String / `BeanMethodQuery` 未用上）。

- [ ] **Step 3: 改 JobDefinitionDto（subjectQuery→SubjectQuery，删 payloadTemplate）**

```java
package com.sstlfsj.rule.job.api.dto;

import com.sstlfsj.rule.job.api.SubjectQuery;

/**
 * Job 定义响应 DTO。
 *
 * @param id             Job 主键
 * @param tenantId       租户 ID
 * @param sceneCode      绑定的 Scene code
 * @param code           Job 编码
 * @param name           Job 名称
 * @param cronExpression Spring 6 段 cron
 * @param subjectQuery   主体查询配置（typed 判别联合）
 * @param eventType      合成 RuleEvent 的 eventType
 * @param status         ACTIVE / DISABLED
 */
public record JobDefinitionDto(
        Long id,
        String tenantId,
        String sceneCode,
        String code,
        String name,
        String cronExpression,
        SubjectQuery subjectQuery,
        String eventType,
        String status
) {}
```

- [ ] **Step 4: 改 JobServiceImpl.toDto（解析 subjectQuery、去 payloadTemplate）**

`JobServiceImpl` 顶部确保已注入 `ObjectMapper`（字段名 `objectMapper`）。若未注入：在类里加 `private final ObjectMapper objectMapper;`（类已用 `@RequiredArgsConstructor` 则自动构造；否则加到构造器），import `tools.jackson.databind.ObjectMapper`。

`toDto` 改为实例方法（因为要用注入的 mapper）并解析 subjectQuery：

```java
private JobDefinitionDto toDto(JobDefinition def) {
    return new JobDefinitionDto(
            def.getId(),
            String.valueOf(def.getTenantId()),
            def.getSceneCode(),
            def.getCode(),
            def.getName(),
            def.getCronExpression(),
            objectMapper.readValue(def.getSubjectQuery(), com.sstlfsj.rule.job.api.SubjectQuery.class),
            def.getEventType(),
            def.getStatus());
}
```

> 注意：原 `toDto` 是 `static`，改成实例方法后，所有调用点 `toDto(x)`（同类内）无需改写，但要去掉 `static`。`payloadTemplate` 参数已从构造里去除。

- [ ] **Step 5: 改 JobControllerTest（删 payloadTemplate 断言、subjectQuery 形状）**

在 `rule-api` 的 `JobControllerTest` 里，凡是构造或断言 `JobDefinitionDto` 的地方：删除 `payloadTemplate` 实参/断言；`subjectQuery` 若以 JSON 路径断言（`$.data.subjectQuery`）改为对象断言 `$.data.subjectQuery.type` == `BEAN_METHOD`、`$.data.subjectQuery.ref` == 预期值。若该 controller 测试 mock 的 service 返回 `JobDefinitionDto`，构造时用：

```java
new JobDefinitionDto(1L, "1", "fraud_check", "demo-daily", "演示",
        "0 0 3 * * *", new com.sstlfsj.rule.job.api.BeanMethodQuery("a#b"),
        "login", "ACTIVE")
```

- [ ] **Step 6: 跑测试确认通过**

```bash
$MVN -pl rule-job-svc -am test
$MVN -pl rule-api -am test -Dtest='JobControllerTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 全 PASS。

- [ ] **Step 7: 提交**

```bash
git add rule-job-svc/src/main/java/com/sstlfsj/rule/job/api/dto/JobDefinitionDto.java \
  rule-job-svc/src/main/java/com/sstlfsj/rule/job/internal/service/JobServiceImpl.java \
  rule-job-svc/src/test/java/com/sstlfsj/rule/job/internal/service/JobServiceImplTest.java \
  rule-api/src/test/java/com/sstlfsj/rule/web/admin/JobControllerTest.java
git commit -m "feat(job): JobDefinitionDto.subjectQuery 类型化 + 删 payloadTemplate 死字段"
```

---

### Task 4: 入口绑定失败 → 400（安全网先于规则 DTO 类型化落地）

**Files:**
- Modify: `rule-api/src/main/java/com/sstlfsj/rule/web/common/GlobalExceptionHandler.java`
- Modify: `rule-app/src/main/resources/application.yml`
- Test: `rule-api/src/test/java/com/sstlfsj/rule/web/common/GlobalExceptionHandlerTest.java`（若不存在则建）

- [ ] **Step 1: 写失败测试（用一个已 typed 的入口触发绑定失败）**

`EvalController` 的 `/api/v1/rule/evaluate` 已绑定 typed `EvalEventRequest`，给它一个类型不符的 body（`occurredAt` 给非法字符串）触发 `HttpMessageNotReadableException`：

`rule-api/src/test/java/com/sstlfsj/rule/web/common/GlobalExceptionHandlerTest.java`:

```java
package com.sstlfsj.rule.web.common;

import com.sstlfsj.rule.eval.api.service.EvalService;
import com.sstlfsj.rule.web.api.EvalController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new EvalController(mock(EvalService.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void malformedBody_returns400_notReadable() throws Exception {
        // occurredAt 给非法值 → Jackson 反序列化失败 → 应 400 而非 500
        String badJson = "{\"tenantId\":\"1\",\"occurredAt\":\"not-a-timestamp\"}";
        mockMvc.perform(post("/api/v1/rule/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("INVALID_ARGUMENT"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
$MVN -pl rule-api -am test -Dtest='GlobalExceptionHandlerTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: FAIL（当前 `HttpMessageNotReadableException` 落到兜底 handler → 500）。

- [ ] **Step 3: 加 HttpMessageNotReadableException handler**

在 `GlobalExceptionHandler` 加 import 与 handler（放在兜底 `handleGeneral` 之前）：

```java
import org.springframework.http.converter.HttpMessageNotReadableException;
```

```java
    /** 请求体反序列化失败（typed 绑定失败：非法 AST type、结构不符等）→ 400。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleNotReadable(HttpMessageNotReadableException ex) {
        return ApiResponse.error("INVALID_ARGUMENT", "请求体格式错误或字段类型不符");
    }
```

- [ ] **Step 4: rule-app 关闭未知字段报错（与 kernel AstJsonCodec 一致，避免多余字段新增 400）**

`rule-app/src/main/resources/application.yml` 的 `spring:` 块下加（若已有 `spring.jackson` 则合并）：

```yaml
spring:
  jackson:
    deserialization:
      fail-on-unknown-properties: false
```

- [ ] **Step 5: 跑测试确认通过**

```bash
$MVN -pl rule-api -am test -Dtest='GlobalExceptionHandlerTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add rule-api/src/main/java/com/sstlfsj/rule/web/common/GlobalExceptionHandler.java \
  rule-app/src/main/resources/application.yml \
  rule-api/src/test/java/com/sstlfsj/rule/web/common/GlobalExceptionHandlerTest.java
git commit -m "feat(api): 请求体反序列化失败映射为 400 + 关闭未知字段报错"
```

---

### Task 5: CreateRuleRequest 4 个 Object 字段类型化

**Files:**
- Create: `rule-api/src/main/java/com/sstlfsj/rule/web/admin/dto/DecisionBindingInput.java`
- Modify: `rule-api/src/main/java/com/sstlfsj/rule/web/admin/dto/CreateRuleRequest.java`
- Test: `rule-api/src/test/java/com/sstlfsj/rule/web/admin/dto/CreateRuleRequestTest.java`、`rule-api/src/test/java/com/sstlfsj/rule/web/admin/RuleControllerTest.java`

> `RuleController.createDraft` 不改：`nodeToString(Object)` 已接受任意对象，typed 对象序列化结果与原 JSON 一致。`ConfigService.createDraft(String...)` 签名不变。

- [ ] **Step 1: 写失败测试（DTO 绑定 + 序列化往返一致）**

替换 `CreateRuleRequestTest`，验证 typed 绑定后序列化回原 JSON 形状：

```java
package com.sstlfsj.rule.web.admin.dto;

import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class CreateRuleRequestTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void bindsTypedConditionAst() {
        String json = """
            {"tenantId":"1","sceneCode":"s","code":"c","name":"n","kind":"AST_BOOLEAN",
             "conditionAst":{"type":"AndNode","children":[]},
             "decisionBindings":[{"decisionCode":"REVIEW"}],
             "preGates":[{"gateType":"ROLLOUT","params":{"percentage":100}}],
             "triggerEventTypes":["login"]}
            """;
        CreateRuleRequest req = mapper.readValue(json, CreateRuleRequest.class);

        assertThat(req.conditionAst()).isInstanceOf(AndNode.class);
        assertThat(req.decisionBindings()).hasSize(1);
        assertThat(req.decisionBindings().get(0).decisionCode()).isEqualTo("REVIEW");
        assertThat(req.preGates()).hasSize(1);
        assertThat(req.preGates().get(0).gateType()).isEqualTo("ROLLOUT");
        assertThat(req.preGates().get(0).params()).containsEntry("percentage", 100);
        assertThat(req.triggerEventTypes()).containsExactly("login");
    }

    @Test
    void decisionBindingsSerializeBackToSameShape() {
        CreateRuleRequest req = new CreateRuleRequest(
                "1", "s", "c", "n", "AST_BOOLEAN",
                new AndNode(java.util.List.of(), null, null),
                java.util.List.of(new DecisionBindingInput("REVIEW")),
                java.util.List.of(),
                java.util.List.of("login"));
        String out = mapper.writeValueAsString(req.decisionBindings());
        // 仅 decisionCode，不引入 null 字段
        assertThat(out).isEqualTo("[{\"decisionCode\":\"REVIEW\"}]");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
$MVN -pl rule-api -am test -Dtest='CreateRuleRequestTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译失败（字段仍 `Object` / `DecisionBindingInput` 不存在）。

- [ ] **Step 3: 写 DecisionBindingInput**

`rule-api/src/main/java/com/sstlfsj/rule/web/admin/dto/DecisionBindingInput.java`:

```java
package com.sstlfsj.rule.web.admin.dto;

/**
 * 创建规则时的决策绑定入参。priority 不在此处——它属于 Decision 实体（Tenant 级，D26），
 * 发布时引擎从 decision_definition.priority 回填进快照。故创建态只引用 decisionCode。
 *
 * @param decisionCode 引用的 Decision 码（必填）
 */
public record DecisionBindingInput(String decisionCode) {}
```

- [ ] **Step 4: 改 CreateRuleRequest 字段类型**

```java
package com.sstlfsj.rule.web.admin.dto;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.PreGateConfig;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/** 创建规则草稿请求体，对应 10-api-contract.md §4.1。 */
public record CreateRuleRequest(
        @NotBlank String tenantId,
        @NotBlank String sceneCode,
        @NotBlank String code,
        @NotBlank String name,
        String kind,
        AstNode conditionAst,
        List<DecisionBindingInput> decisionBindings,
        List<PreGateConfig> preGates,
        List<String> triggerEventTypes
) {}
```

- [ ] **Step 5: 跑 DTO 测试确认通过**

```bash
$MVN -pl rule-api -am test -Dtest='CreateRuleRequestTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS。

- [ ] **Step 6: 跑 RuleControllerTest 修红（若构造 CreateRuleRequest 用了 Object 实参）**

`RuleControllerTest` 里凡构造 `CreateRuleRequest` 的地方，把 `Object` 实参换成 typed：`conditionAst` 用 `new AndNode(List.of(), null, null)`、`decisionBindings` 用 `List.of(new DecisionBindingInput("REVIEW"))`、`preGates` 用 `List.of()`、`triggerEventTypes` 用 `List.of("login")`。若测试是发原始 JSON 字符串（MockMvc content），则 body 无需改（形状一致），仅需确认 200。

```bash
$MVN -pl rule-api -am test -Dtest='RuleControllerTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS。

- [ ] **Step 7: 提交**

```bash
git add rule-api/src/main/java/com/sstlfsj/rule/web/admin/dto/DecisionBindingInput.java \
  rule-api/src/main/java/com/sstlfsj/rule/web/admin/dto/CreateRuleRequest.java \
  rule-api/src/test/java/com/sstlfsj/rule/web/admin/dto/CreateRuleRequestTest.java \
  rule-api/src/test/java/com/sstlfsj/rule/web/admin/RuleControllerTest.java
git commit -m "feat(api): CreateRuleRequest 字段类型化（AstNode/typed lists）"
```

---

### Task 6: RuleDetailVO 读回类型化

**Files:**
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/RuleDetailVO.java`
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/ConfigServiceImpl.java:88-112`
- Test: `rule-config-svc/src/test/java/...`（getRuleDetail 相关单测；若无则在集成测验证）

- [ ] **Step 1: 写/改测试（读回 typed，序列化形状不变）**

在 config-svc 现有 `ConfigServiceImpl` 测试（或新建 `ConfigServiceRuleDetailTest`）里，mock `ruleVersionMapper.findActiveVersion` 返回一个 `RuleVersion`，其 `conditionAst="{\"type\":\"AndNode\",\"children\":[],\"displayLabel\":null,\"weight\":null}"`、`decisionBindings="[{\"decisionCode\":\"BLOCK\",\"priority\":100}]"`，断言：

```java
RuleDetailVO vo = configService.getRuleDetail("1", 1L);
assertThat(vo.conditionAst()).isInstanceOf(com.sstlfsj.rule.kernel.api.model.ast.AndNode.class);
assertThat(vo.decisionBindings()).hasSize(1);
assertThat(vo.decisionBindings().get(0).decisionCode()).isEqualTo("BLOCK");
assertThat(vo.decisionBindings().get(0).priority()).isEqualTo(100);
```

> 若 config-svc 无合适单测脚手架，改在 `rule-api` 的 `RuleControllerTest`/集成测里对 `GET /admin/v1/rules/{id}` 断言 `$.data.conditionAst.type == "AndNode"`、`$.data.decisionBindings[0].decisionCode == "BLOCK"`、`$.data.decisionBindings[0].priority == 100`。

- [ ] **Step 2: 跑测试确认失败**

```bash
$MVN -pl rule-config-svc -am test -Dtest='ConfigServiceRuleDetailTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译失败（`RuleDetailVO` 字段仍 `Object`，无 `.decisionCode()`）。

- [ ] **Step 3: 改 RuleDetailVO**

```java
package com.sstlfsj.rule.config.api.dto;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;

import java.util.List;

/** 规则详情：规则定义基本信息 + 当前 ACTIVE 版本的条件 AST 与决策绑定，供前端编辑回填。 */
public record RuleDetailVO(
        Long ruleDefinitionId, String code, String name, String status, String kind,
        String sceneCode, AstNode conditionAst, List<DecisionBinding> decisionBindings,
        Long currentVersionId) {}
```

- [ ] **Step 4: 改 ConfigServiceImpl.getRuleDetail（typed 反序列化）**

把 `getRuleDetail` 里 `parseJson(...)` 两处替换为 typed 解析，并把通用 `parseJson` 拆成两个专用方法：

```java
    @Override
    public RuleDetailVO getRuleDetail(String tenantId, Long ruleId) {
        RuleDefinition rule = ruleDefinitionMapper.selectById(ruleId);
        if (rule == null || !tenantId.equals(String.valueOf(rule.getTenantId()))) {
            throw new IllegalArgumentException("规则不存在: id=" + ruleId);
        }
        SceneDef scene = sceneMapper.selectById(rule.getSceneId());
        RuleVersion active = ruleVersionMapper.findActiveVersion(ruleId);
        return new RuleDetailVO(
                rule.getId(), rule.getCode(), rule.getName(), rule.getStatus(), rule.getKind(),
                scene != null ? scene.getCode() : null,
                active != null ? parseAst(active.getConditionAst()) : null,
                active != null ? parseDecisionBindings(active.getDecisionBindings()) : null,
                active != null ? active.getId() : null);
    }

    /** 把库中 conditionAst JSON 反序列化为 AstNode（多态）；空值返回 null。 */
    private com.sstlfsj.rule.kernel.api.model.ast.AstNode parseAst(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, com.sstlfsj.rule.kernel.api.model.ast.AstNode.class);
        } catch (Exception e) {
            throw new IllegalStateException("conditionAst 反序列化失败", e);
        }
    }

    /** 把库中 decisionBindings JSON 反序列化为 List<DecisionBinding>；空值返回 null。 */
    private java.util.List<com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding>
            parseDecisionBindings(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json,
                    new tools.jackson.core.type.TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("decisionBindings 反序列化失败", e);
        }
    }
```

> 删除原 `private Object parseJson(String json)` 方法（已被上面两个取代；若它被别处引用则保留）。

- [ ] **Step 5: 跑测试确认通过**

```bash
$MVN -pl rule-config-svc -am test -Dtest='ConfigServiceRuleDetailTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/RuleDetailVO.java \
  rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/ConfigServiceImpl.java \
  rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/service/ConfigServiceRuleDetailTest.java
git commit -m "feat(config): RuleDetailVO 读回类型化（AstNode + List<DecisionBinding>）"
```

---

### Task 7: 全量回归 + 文档对齐

**Files:** `docs/01-concepts.md` / `docs/10-api-contract.md`（仅在描述与改动不符时微调）

- [ ] **Step 1: 全量回归（确认线上 JSON 契约不变）**

```bash
$MVN install -DskipTests -q
$MVN -pl rule-kernel,rule-config-svc,rule-eval-svc,rule-job-svc,rule-api test
$MVN -pl rule-app test -Dtest='KernelArchTest,ModulithStructureTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 全绿。

- [ ] **Step 2: 文档核对**

检查 `docs/10-api-contract.md` §4.1（CreateRule）/ §4 RuleDetail / Job 接口：本次仅删 `JobDefinitionDto.payloadTemplate`，其余 JSON 形状不变。若 Job 接口文档列了 `payloadTemplate` 响应字段，删除该行；其它无需改（契约 JSON 未变）。

- [ ] **Step 3: 提交文档（若有改动）**

```bash
git add docs/10-api-contract.md
git commit -m "docs(api-contract): Job 响应去 payloadTemplate 字段（D49 遗留）"
```

- [ ] **Step 4: 派 rule-engine-reviewer 审代码↔文档对齐**（按 CLAUDE.md，仅显式调用）

---

## 验收

- `CreateRuleRequest` / `RuleDetailVO` / `JobDefinitionDto` 无 `Object` / 弱类型 String JSON 字段；`payloadTemplate` 已删。
- 非法请求体（错误 AST type、类型不符）→ 400 INVALID_ARGUMENT，不再 500。
- 字段名/语义不变（现有集成测全绿）；可接受的形状差异两处：Job 响应去 `payloadTemplate`；`conditionAst` 输出补齐可选字段 null（向后兼容，见顶部不变量说明）。
- 动态字段（payload / providedMetrics / *.params / defaultParams）仍 Map，未动。
- kernel、持久化实体层未动（二期 TypeHandler 另起）。
