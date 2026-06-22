# 连接器标准化 P1 — 连接器写侧实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让连接器（声明式 EXTERNAL_HTTP 描述符）可经 `/admin/v1/connectors` API 创建/更新/列出，写入时做安全校验、存进 `connector_definition` 表、发出审计与失效事件——全程不碰 eval-svc，独立编译+测试通过。

**Architecture:** 照抄 `metric_definition` 写链路（controller→service→mapper→entity→Jackson3TypeHandler JSON 列）。描述符是 typed record 聚合，单 JSON 列存储。校验在写时做（连接器无 publish 流程）。失效事件 `ConnectorChangedEvent` 走 A 类跨模块集成（`ApplicationEventPublisher`，提交后 `@ApplicationModuleListener` 由 eval 监听，本计划只发不听）。

**Tech Stack:** Java 25 / Spring Boot 4 / Spring Modulith / MyBatis-Plus（`BaseMapper` + `LambdaQueryWrapper` default 方法）/ MapStruct / Lombok / Jackson3 / Flyway / JUnit5 + AssertJ。

**本计划范围红线：** 不改 kernel（MetricFetchError/MetricValue.reason 留 P2）；不改 `MetricResourceCatalog` SPI（加 `connectorNames()` 留 P2，避免破坏 eval 侧 impl 编译）；不改 `MetricSafetyValidator`（metric 引用 connector 的闭合校验留 P2）；不做 `:test` 端点（留 P3）；不做前端（留 P4）。

**全序列：** P1 写侧（本文） → P2 评估运行时 → P3 测试端点+conformance → P4 前端+docs/e2e。

**环境：** 跑测试前先用 `mvn-env` skill 设环境，命令用 `$MVN`。跨模块改动带 `-am`，一轮结束用 `$MVN clean test` 兜底。

**包根：** `com.sstlfsj.rule`。**测试方法名英文，注释中文。**

---

## 文件结构

**Create（config-svc）`rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/connector/`：**
- `HttpMethod.java` `CompareOp.java` `AuthKind.java` `RetryTrigger.java` — 封闭取值 enum
- `TemplateParam.java` `Predicate.java` `ResponseMapping.java` `HttpRequestTemplate.java` — 请求/响应 typed record
- `AuthScheme.java`（sealed）+ `StaticHeaderAuth.java` `BearerAuth.java` `OAuth2ClientCredentialsAuth.java` — 鉴权 typed
- `CircuitBreakerPolicy.java` `ResiliencePolicy.java` `ErrorMatch.java` `ErrorRule.java` — 弹性/错误映射 typed
- `ConnectorDescriptor.java` — 描述符聚合根

**Create（config-svc）`rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/`：**
- `ConnectorStatus.java` — enum ACTIVE/DISABLED
- `ConnectorDefinition.java` — 实体（`autoResultMap` + 单 JSON 列 descriptor）

**Create（config-svc）`rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/repository/`：**
- `ConnectorDefinitionMapper.java` — `BaseMapper` + default 查询方法

**Create（config-svc）`rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/`：**
- `service/ConnectorWriteService.java`（含内嵌 `ConnectorWriteCommand`、`ConnectorView`）
- `event/ConnectorChangedEvent.java`

**Create（config-svc）`rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/`：**
- `publish/ConnectorSafetyValidator.java` — package-private，写时校验
- `service/ConnectorWriteServiceImpl.java`

**Create（config-svc）迁移：** `rule-config-svc/src/main/resources/db/migration/V1_34__connector_definition.sql`

**Create（rule-api）`rule-api/src/main/java/com/sstlfsj/rule/web/admin/`：**
- `ConnectorController.java`（list/create/update，无 :test）
- `dto/ConnectorRequest.java` `dto/ConnectorResponse.java`
- `convert/ConnectorConvert.java`（MapStruct）

**Test：** 各 record/enum 单测、mapper JSON 往返集成测试、validator 单测、serviceImpl 单测、controller 测，路径镜像被测类（`src/test/java/...` 同包）。

---

## Task 1: 描述符枚举（封闭取值）

**Files:**
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/connector/HttpMethod.java`
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/connector/CompareOp.java`
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/connector/AuthKind.java`
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/connector/RetryTrigger.java`
- Test: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/api/connector/ConnectorEnumsTest.java`

- [ ] **Step 1: 写枚举（4 个文件）**

`HttpMethod.java`：
```java
package com.sstlfsj.rule.config.api.connector;

/** 连接器请求方法（封闭取值，== 描述符 JSON 中 request.method 字面量）。 */
public enum HttpMethod {
    GET, POST, PUT
}
```

`CompareOp.java`：
```java
package com.sstlfsj.rule.config.api.connector;

/** 响应成功判定（successWhen）比较算子（封闭取值）。 */
public enum CompareOp {
    EQ, NE, GT, GE, LT, LE
}
```

`AuthKind.java`：
```java
package com.sstlfsj.rule.config.api.connector;

/** 连接器鉴权方案种类（封闭取值，作 AuthScheme 多态判别）。 */
public enum AuthKind {
    STATIC_HEADER, BEARER, OAUTH2_CLIENT_CREDENTIALS
}
```

`RetryTrigger.java`：
```java
package com.sstlfsj.rule.config.api.connector;

/** 重试触发条件（封闭取值）。 */
public enum RetryTrigger {
    TIMEOUT, UPSTREAM_5XX
}
```

- [ ] **Step 2: 写枚举存在性/取值测试**

`ConnectorEnumsTest.java`：
```java
package com.sstlfsj.rule.config.api.connector;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ConnectorEnumsTest {

    @Test
    void httpMethodHasExpectedValues() {
        assertThat(HttpMethod.values()).containsExactly(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT);
    }

    @Test
    void authKindHasThreeSchemes() {
        assertThat(AuthKind.values()).containsExactly(
                AuthKind.STATIC_HEADER, AuthKind.BEARER, AuthKind.OAUTH2_CLIENT_CREDENTIALS);
    }

    @Test
    void compareOpAndRetryTriggerResolveByName() {
        assertThat(CompareOp.valueOf("GE")).isEqualTo(CompareOp.GE);
        assertThat(RetryTrigger.valueOf("UPSTREAM_5XX")).isEqualTo(RetryTrigger.UPSTREAM_5XX);
    }
}
```

- [ ] **Step 3: 跑测试**

Run: `$MVN -pl rule-config-svc test -Dtest=ConnectorEnumsTest`
Expected: PASS（3 个测试绿）

- [ ] **Step 4: Commit**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/connector/HttpMethod.java \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/connector/CompareOp.java \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/connector/AuthKind.java \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/connector/RetryTrigger.java \
        rule-config-svc/src/test/java/com/sstlfsj/rule/config/api/connector/ConnectorEnumsTest.java
git commit -m "feat(config): 连接器描述符封闭取值枚举"
```

---

## Task 2: 描述符 typed record 套件

**Files:**
- Create: `.../api/connector/TemplateParam.java` `Predicate.java` `ResponseMapping.java` `HttpRequestTemplate.java`
- Create: `.../api/connector/AuthScheme.java`（sealed）`StaticHeaderAuth.java` `BearerAuth.java` `OAuth2ClientCredentialsAuth.java`
- Create: `.../api/connector/CircuitBreakerPolicy.java` `ResiliencePolicy.java` `ErrorMatch.java` `ErrorRule.java`
- Create: `.../api/connector/ConnectorDescriptor.java`
- Test: `.../api/connector/ConnectorDescriptorJsonTest.java`

- [ ] **Step 1: 写请求/响应 record**

`TemplateParam.java`：
```java
package com.sstlfsj.rule.config.api.connector;

/**
 * 请求模板参数（query / header 用）。
 *
 * @param name          参数名
 * @param valueTemplate 含占位符的值模板，如 "{vars.userId}"
 */
public record TemplateParam(String name, String valueTemplate) {}
```

`Predicate.java`：
```java
package com.sstlfsj.rule.config.api.connector;

/**
 * 响应成功判定谓词。{@code value} 为异构标量字面量（Number/String/Boolean），
 * 是 CLAUDE.md 允许的"确实无固定类型"例外。
 *
 * @param path  点号 jsonPath，如 "code"
 * @param op    比较算子
 * @param value 比较字面量
 */
public record Predicate(String path, CompareOp op, Object value) {}
```

`ResponseMapping.java`：
```java
package com.sstlfsj.rule.config.api.connector;

/**
 * 响应映射：从任意外壳归一到 metric 值。
 *
 * @param successWhen 成功判定谓词
 * @param valuePath   取值点号 jsonPath，如 "data.score"
 */
public record ResponseMapping(Predicate successWhen, String valuePath) {}
```

`HttpRequestTemplate.java`（含 Lombok `@Builder`，字段 ≥4）：
```java
package com.sstlfsj.rule.config.api.connector;

import lombok.Builder;
import java.util.List;

/**
 * HTTP 请求模板。占位符 {payload.x}/{params.x}/{vars.x}/{subject.x}/{now}/{subjectId}/{tenantId}。
 *
 * @param method       请求方法
 * @param pathTemplate 含占位符的相对路径
 * @param query        query 参数模板列表
 * @param headers      header 模板列表
 * @param bodyTemplate POST/PUT 的请求体模板，含占位符；GET 为 null
 */
@Builder
public record HttpRequestTemplate(
        HttpMethod method,
        String pathTemplate,
        List<TemplateParam> query,
        List<TemplateParam> headers,
        String bodyTemplate) {}
```

- [ ] **Step 2: 写鉴权 sealed 套件（Jackson 多态按 kind 判别）**

`AuthScheme.java`：
```java
package com.sstlfsj.rule.config.api.connector;

import tools.jackson.annotation.JsonSubTypes;
import tools.jackson.annotation.JsonTypeInfo;

/** 连接器鉴权方案（typed 多态，按 kind 判别）。密钥以 *Ref 引用 infra，不内联明文。 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = StaticHeaderAuth.class, name = "STATIC_HEADER"),
        @JsonSubTypes.Type(value = BearerAuth.class, name = "BEARER"),
        @JsonSubTypes.Type(value = OAuth2ClientCredentialsAuth.class, name = "OAUTH2_CLIENT_CREDENTIALS")
})
public sealed interface AuthScheme permits StaticHeaderAuth, BearerAuth, OAuth2ClientCredentialsAuth {
    /** @return 鉴权种类。 */
    AuthKind kind();
}
```

`StaticHeaderAuth.java`：
```java
package com.sstlfsj.rule.config.api.connector;

/**
 * 静态请求头鉴权。
 *
 * @param headerName    头名，如 "X-Api-Key"
 * @param credentialRef infra 凭证引用名（值不落描述符）
 */
public record StaticHeaderAuth(String headerName, String credentialRef) implements AuthScheme {
    @Override public AuthKind kind() { return AuthKind.STATIC_HEADER; }
}
```

`BearerAuth.java`：
```java
package com.sstlfsj.rule.config.api.connector;

/**
 * Bearer token 鉴权。
 *
 * @param tokenRef infra 凭证引用名
 */
public record BearerAuth(String tokenRef) implements AuthScheme {
    @Override public AuthKind kind() { return AuthKind.BEARER; }
}
```

`OAuth2ClientCredentialsAuth.java`（`@Builder`，字段 ≥4）：
```java
package com.sstlfsj.rule.config.api.connector;

import lombok.Builder;
import java.util.List;

/**
 * OAuth2 client-credentials 鉴权。token 由 eval 侧按 *Ref 取凭证后换取并缓存（P2 实现）。
 *
 * @param tokenUrl        取 token 的 URL
 * @param clientIdRef     clientId 凭证引用名
 * @param clientSecretRef clientSecret 凭证引用名
 * @param scopes          申请 scope 列表
 */
@Builder
public record OAuth2ClientCredentialsAuth(
        String tokenUrl, String clientIdRef, String clientSecretRef, List<String> scopes) implements AuthScheme {
    @Override public AuthKind kind() { return AuthKind.OAUTH2_CLIENT_CREDENTIALS; }
}
```

- [ ] **Step 3: 写弹性/错误映射 record**

`CircuitBreakerPolicy.java`（`@Builder`）：
```java
package com.sstlfsj.rule.config.api.connector;

import lombok.Builder;

/**
 * 熔断策略。
 *
 * @param failureRateThreshold 失败率阈值（0-100）
 * @param windowSeconds        统计窗口秒
 * @param openSeconds          打开后保持秒
 */
@Builder
public record CircuitBreakerPolicy(int failureRateThreshold, int windowSeconds, int openSeconds) {}
```

`ResiliencePolicy.java`（`@Builder`，字段 ≥4）：
```java
package com.sstlfsj.rule.config.api.connector;

import lombok.Builder;
import java.util.Set;

/**
 * 弹性策略。retry 仅对幂等请求生效；SQL 侧仅用到 readTimeoutMs（statement 超时，P2）。
 *
 * @param connectTimeoutMs 连接超时毫秒
 * @param readTimeoutMs    读超时毫秒
 * @param retries          重试次数
 * @param retryOn          触发重试的条件
 * @param circuitBreaker   熔断策略，可为 null（不启用）
 */
@Builder
public record ResiliencePolicy(
        int connectTimeoutMs,
        int readTimeoutMs,
        int retries,
        Set<RetryTrigger> retryOn,
        CircuitBreakerPolicy circuitBreaker) {}
```

`ErrorMatch.java`：
```java
package com.sstlfsj.rule.config.api.connector;

/**
 * 错误匹配条件：HTTP 状态区间或响应信封码。两者择一非 null。
 *
 * @param statusFrom   状态码下界（含），null 表示不按状态匹配
 * @param statusTo     状态码上界（含）
 * @param envelopeCode 信封业务码字面量（与 successWhen.path 同位），null 表示不按信封码匹配
 */
public record ErrorMatch(Integer statusFrom, Integer statusTo, Object envelopeCode) {}
```

`ErrorRule.java`：
```java
package com.sstlfsj.rule.config.api.connector;

/**
 * 错误映射规则：命中 when 时归一到指定细码（细码字面量在 P2 的 MetricFetchError 落地，
 * 此处存 String 名以免 config 反依赖 kernel enum）。
 *
 * @param when 匹配条件
 * @param to   目标细码名，如 "UPSTREAM_ERROR"
 */
public record ErrorRule(ErrorMatch when, String to) {}
```

- [ ] **Step 4: 写描述符聚合根**

`ConnectorDescriptor.java`（`@Builder`，字段 ≥4）：
```java
package com.sstlfsj.rule.config.api.connector;

import lombok.Builder;
import java.util.List;

/**
 * 声明式 HTTP 连接器描述符（设计 §5）。整体作为 connector_definition 单 JSON 列存储。
 *
 * @param endpointRef  指向已注册传输层 Endpoint 名
 * @param request      请求模板
 * @param response     响应映射
 * @param auth         鉴权方案
 * @param resilience   弹性策略
 * @param errorMapping 错误映射规则列表
 */
@Builder
public record ConnectorDescriptor(
        String endpointRef,
        HttpRequestTemplate request,
        ResponseMapping response,
        AuthScheme auth,
        ResiliencePolicy resilience,
        List<ErrorRule> errorMapping) {}
```

- [ ] **Step 5: 写 Jackson 往返测试（多态鉴权 + 嵌套）**

`ConnectorDescriptorJsonTest.java`：
```java
package com.sstlfsj.rule.config.api.connector;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectorDescriptorJsonTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void roundTripsWithOAuth2AndErrorMapping() {
        ConnectorDescriptor d = ConnectorDescriptor.builder()
                .endpointRef("risk-svc")
                .request(HttpRequestTemplate.builder()
                        .method(HttpMethod.POST)
                        .pathTemplate("/score/{subjectId}")
                        .query(List.of(new TemplateParam("region", "{payload.region}")))
                        .headers(List.of(new TemplateParam("X-Trace", "{now}")))
                        .bodyTemplate("{\"f\":\"{vars.feature}\"}")
                        .build())
                .response(new ResponseMapping(new Predicate("code", CompareOp.EQ, 0), "data.score"))
                .auth(OAuth2ClientCredentialsAuth.builder()
                        .tokenUrl("https://auth/token").clientIdRef("cid").clientSecretRef("sec")
                        .scopes(List.of("score")).build())
                .resilience(ResiliencePolicy.builder()
                        .connectTimeoutMs(200).readTimeoutMs(300).retries(1)
                        .retryOn(Set.of(RetryTrigger.TIMEOUT))
                        .circuitBreaker(CircuitBreakerPolicy.builder()
                                .failureRateThreshold(50).windowSeconds(10).openSeconds(30).build())
                        .build())
                .errorMapping(List.of(new ErrorRule(new ErrorMatch(500, 599, null), "UPSTREAM_ERROR")))
                .build();

        String json = mapper.writeValueAsString(d);
        ConnectorDescriptor back = mapper.readValue(json, ConnectorDescriptor.class);

        assertThat(back).isEqualTo(d);
        assertThat(back.auth()).isInstanceOf(OAuth2ClientCredentialsAuth.class);
        assertThat(back.auth().kind()).isEqualTo(AuthKind.OAUTH2_CLIENT_CREDENTIALS);
        assertThat(json).contains("\"kind\":\"OAUTH2_CLIENT_CREDENTIALS\"");
    }

    @Test
    void roundTripsStaticHeaderGet() {
        ConnectorDescriptor d = ConnectorDescriptor.builder()
                .endpointRef("ip-rep")
                .request(HttpRequestTemplate.builder()
                        .method(HttpMethod.GET).pathTemplate("/ip/{payload.ip}")
                        .query(List.of()).headers(List.of()).bodyTemplate(null).build())
                .response(new ResponseMapping(new Predicate("ok", CompareOp.EQ, true), "rep"))
                .auth(new StaticHeaderAuth("X-Api-Key", "ipRepKey"))
                .resilience(ResiliencePolicy.builder()
                        .connectTimeoutMs(200).readTimeoutMs(300).retries(0)
                        .retryOn(Set.of()).circuitBreaker(null).build())
                .errorMapping(List.of())
                .build();

        ConnectorDescriptor back = mapper.readValue(mapper.writeValueAsString(d), ConnectorDescriptor.class);
        assertThat(back).isEqualTo(d);
        assertThat(back.auth()).isInstanceOf(StaticHeaderAuth.class);
    }
}
```

- [ ] **Step 6: 跑测试**

Run: `$MVN -pl rule-config-svc test -Dtest=ConnectorDescriptorJsonTest`
Expected: PASS。若多态反序列化报错（缺判别字段），检查 `@JsonTypeInfo` 的 `tools.jackson.annotation` 包导入是否正确（项目用 Jackson3，包名是 `tools.jackson` 非 `com.fasterxml`）。

- [ ] **Step 7: Commit**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/connector/ \
        rule-config-svc/src/test/java/com/sstlfsj/rule/config/api/connector/ConnectorDescriptorJsonTest.java
git commit -m "feat(config): 连接器描述符 typed record 套件 + Jackson 往返"
```

---

## Task 3: ConnectorStatus + 实体 + Mapper

**Files:**
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/ConnectorStatus.java`
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/ConnectorDefinition.java`
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/repository/ConnectorDefinitionMapper.java`

参考实体范式：`rule-config-svc/.../internal/domain/MetricDefinition.java`、`RuleVersion.java`（单 JSON 列）。参考 Mapper：`rule-config-svc/.../internal/repository/MetricDefinitionMapper.java`。

- [ ] **Step 1: 写 ConnectorStatus**

```java
package com.sstlfsj.rule.config.internal.domain;

/** 连接器生命周期状态（枚举名 == DB varchar 字面量，MyBatis-Plus 全局 enum TypeHandler 往返）。
 * 连接器不做 per-version 冻结，无 SUPERSEDED。 */
public enum ConnectorStatus {
    ACTIVE, DISABLED
}
```

- [ ] **Step 2: 写实体（autoResultMap + 单 JSON 列 descriptor）**

`ConnectorDefinition.java`：
```java
package com.sstlfsj.rule.config.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;
import com.sstlfsj.rule.config.api.connector.ConnectorDescriptor;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 连接器定义实体。descriptor 整体作为单 JSON 列由 TypeHandler 转换（模板 = RuleVersion）。 */
@Getter
@Setter
@TableName(value = "connector_definition", autoResultMap = true)
public class ConnectorDefinition {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String connectorCode;
    private String name;

    @TableField(typeHandler = Jackson3TypeHandler.class)
    private ConnectorDescriptor descriptor;

    private ConnectorStatus status;
    private String createdBy;
    private LocalDateTime createdAt;
    private String updatedBy;
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 3: 写 Mapper（default 方法封单表查询）**

`ConnectorDefinitionMapper.java`：
```java
package com.sstlfsj.rule.config.internal.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.config.internal.domain.ConnectorDefinition;
import com.sstlfsj.rule.config.internal.domain.ConnectorStatus;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** 连接器定义单表查询（BaseMapper + default 封装，不在 service 散拼 wrapper）。 */
@Mapper
public interface ConnectorDefinitionMapper extends BaseMapper<ConnectorDefinition> {

    /** 取租户内某连接器（任意状态），null 表示不存在。 */
    default ConnectorDefinition findByCode(Long tenantId, String connectorCode) {
        return selectOne(new LambdaQueryWrapper<ConnectorDefinition>()
                .eq(ConnectorDefinition::getTenantId, tenantId)
                .eq(ConnectorDefinition::getConnectorCode, connectorCode));
    }

    /** 取租户内全部 ACTIVE 连接器。 */
    default List<ConnectorDefinition> findActiveByTenant(Long tenantId) {
        return selectList(new LambdaQueryWrapper<ConnectorDefinition>()
                .eq(ConnectorDefinition::getTenantId, tenantId)
                .eq(ConnectorDefinition::getStatus, ConnectorStatus.ACTIVE));
    }
}
```

- [ ] **Step 4: 编译检查（无独立单测，下 Task 集成测试验往返）**

Run: `$MVN -pl rule-config-svc -am test-compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/ConnectorStatus.java \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/ConnectorDefinition.java \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/repository/ConnectorDefinitionMapper.java
git commit -m "feat(config): connector_definition 实体 + Mapper"
```

---

## Task 4: 迁移 V1_34 + JSON 列往返集成测试

**Files:**
- Create: `rule-config-svc/src/main/resources/db/migration/V1_34__connector_definition.sql`
- Test: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/repository/ConnectorDefinitionMapperIT.java`

参考迁移：`V1_0__init_schema.sql` 的 `metric_definition` 段（status 用 VARCHAR，非 MySQL ENUM——CLAUDE.md 强制 + V1_11 已收口）。参考 JSON 往返集成测试范式：现有 metric/rule mapper 的集成测试（查 `rule-config-svc/src/test` 下带 `@SpringBootTest` 或 testcontainers/H2 的 mapper 测试，照其建表/启动方式）。

- [ ] **Step 1: 写迁移**

`V1_34__connector_definition.sql`：
```sql
CREATE TABLE IF NOT EXISTS connector_definition (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id       BIGINT       NOT NULL,
  connector_code  VARCHAR(128) NOT NULL COMMENT 'connectorCode，租户内唯一',
  name            VARCHAR(128) NOT NULL,
  descriptor      JSON         NOT NULL COMMENT '声明式连接器描述符（request/response/auth/resilience/errorMapping）',
  status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '取值: ACTIVE/DISABLED',
  created_by      VARCHAR(64),
  created_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by      VARCHAR(64),
  updated_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_tenant_connector (tenant_id, connector_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='连接器定义';
```

- [ ] **Step 2: 写 JSON 往返集成测试**

先用 `Grep`/`Glob` 找现有 mapper 集成测试范式（搜 `rule-config-svc/src/test` 中 `extends` 测试基类或 `@SpringBootTest`/`@MybatisPlusTest`/testcontainers 的类），**照该范式建测试类**（数据库启动方式必须与既有一致，不要自创）。测试逻辑：

```java
// 类注解照既有 mapper 集成测试基类；注入 ConnectorDefinitionMapper mapper;
@Test
void insertAndReadBackPreservesTypedDescriptor() {
    ConnectorDefinition c = new ConnectorDefinition();
    c.setTenantId(1L);
    c.setConnectorCode("risk-svc");
    c.setName("风控打分");
    c.setStatus(ConnectorStatus.ACTIVE);
    c.setDescriptor(ConnectorDescriptor.builder()
            .endpointRef("risk")
            .request(HttpRequestTemplate.builder()
                    .method(HttpMethod.POST).pathTemplate("/score/{subjectId}")
                    .query(List.of()).headers(List.of()).bodyTemplate("{}").build())
            .response(new ResponseMapping(new Predicate("code", CompareOp.EQ, 0), "data.score"))
            .auth(new BearerAuth("riskToken"))
            .resilience(ResiliencePolicy.builder()
                    .connectTimeoutMs(200).readTimeoutMs(300).retries(0)
                    .retryOn(Set.of()).circuitBreaker(null).build())
            .errorMapping(List.of())
            .build());

    mapper.insert(c);

    ConnectorDefinition back = mapper.findByCode(1L, "risk-svc");
    assertThat(back).isNotNull();
    assertThat(back.getStatus()).isEqualTo(ConnectorStatus.ACTIVE);
    assertThat(back.getDescriptor().auth()).isInstanceOf(BearerAuth.class);
    assertThat(back.getDescriptor().response().valuePath()).isEqualTo("data.score");
}
```

- [ ] **Step 3: 跑集成测试**

Run: `$MVN -pl rule-config-svc test -Dtest=ConnectorDefinitionMapperIT`
Expected: PASS（typed descriptor JSON 真往返，status enum↔varchar 真往返）。若 `autoResultMap` 未生效会读回 descriptor=null —— 红旗，检查实体 `@TableName(autoResultMap=true)` 与字段 `@TableField(typeHandler=...)`。

- [ ] **Step 4: Commit**

```bash
git add rule-config-svc/src/main/resources/db/migration/V1_34__connector_definition.sql \
        rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/repository/ConnectorDefinitionMapperIT.java
git commit -m "feat(config): connector_definition 建表迁移 + JSON 列往返集成测试"
```

---

## Task 5: 写服务接口 + 命令 + 失效事件

**Files:**
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/event/ConnectorChangedEvent.java`
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/service/ConnectorWriteService.java`

参考：`rule-config-svc/.../api/event/SceneChangedEvent.java`、`rule-config-svc/.../api/service/MetricWriteService.java`（含内嵌 record 命令）。

- [ ] **Step 1: 写失效事件（A 类跨模块集成，api/event 公开契约）**

`ConnectorChangedEvent.java`：
```java
package com.sstlfsj.rule.config.api.event;

/**
 * 连接器变更事件（A 类跨模块集成）：config 写后通知 eval 失效连接器缓存。
 * 用 ApplicationEventPublisher 发，eval 侧 @ApplicationModuleListener 提交后异步消费（本计划只发不听）。
 *
 * @param tenantId      租户 id
 * @param connectorCode 连接器编码
 */
public record ConnectorChangedEvent(String tenantId, String connectorCode) {}
```

- [ ] **Step 2: 写服务接口 + 命令 + 视图（typed）**

`ConnectorWriteService.java`：
```java
package com.sstlfsj.rule.config.api.service;

import com.sstlfsj.rule.config.api.connector.ConnectorDescriptor;

import java.util.List;

/** 连接器写服务（CRUD，无 publish 流程，校验在写时做）。 */
public interface ConnectorWriteService {

    /**
     * 创建连接器（置 ACTIVE，写时校验，发审计 + 失效事件）。
     *
     * @return 新建行 id
     */
    Long create(Long tenantId, String connectorCode, ConnectorWriteCommand cmd, String actorId);

    /**
     * 原地更新连接器描述符（不升版，发审计 + 失效事件）。
     *
     * @return 受影响行数
     */
    int update(Long tenantId, String connectorCode, ConnectorWriteCommand cmd, String actorId);

    /** 列出租户内全部 ACTIVE 连接器。 */
    List<ConnectorView> listActive(Long tenantId);

    /**
     * 写命令（typed）。
     *
     * @param name       展示名
     * @param descriptor 连接器描述符
     */
    record ConnectorWriteCommand(String name, ConnectorDescriptor descriptor) {}

    /**
     * 列表视图（出契约边界 status 以 String）。
     *
     * @param connectorCode 编码
     * @param name          名称
     * @param status        状态名
     */
    record ConnectorView(String connectorCode, String name, String status) {}
}
```

- [ ] **Step 3: 编译检查**

Run: `$MVN -pl rule-config-svc test-compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/event/ConnectorChangedEvent.java \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/service/ConnectorWriteService.java
git commit -m "feat(config): 连接器写服务接口 + 命令 + ConnectorChangedEvent"
```

---

## Task 6: 写时安全校验器

**Files:**
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/ConnectorSafetyValidator.java`
- Test: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/ConnectorSafetyValidatorTest.java`

参考：`rule-config-svc/.../internal/publish/MetricSafetyValidator.java`（package-private，new 出来用，资源名集合为 null 时跳过资源校验）。

- [ ] **Step 1: 写失败测试**

`ConnectorSafetyValidatorTest.java`：
```java
package com.sstlfsj.rule.config.internal.publish;

import com.sstlfsj.rule.config.api.connector.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class ConnectorSafetyValidatorTest {

    private final ConnectorSafetyValidator validator = new ConnectorSafetyValidator();

    private ConnectorDescriptor descriptor(String endpointRef, String pathTemplate) {
        return ConnectorDescriptor.builder()
                .endpointRef(endpointRef)
                .request(HttpRequestTemplate.builder()
                        .method(HttpMethod.GET).pathTemplate(pathTemplate)
                        .query(List.of()).headers(List.of()).bodyTemplate(null).build())
                .response(new ResponseMapping(new Predicate("ok", CompareOp.EQ, true), "v"))
                .auth(new StaticHeaderAuth("X-Key", "k"))
                .resilience(ResiliencePolicy.builder()
                        .connectTimeoutMs(200).readTimeoutMs(300).retries(0)
                        .retryOn(Set.of()).circuitBreaker(null).build())
                .errorMapping(List.of())
                .build();
    }

    @Test
    void rejectsUnregisteredEndpoint() {
        assertThatThrownBy(() -> validator.validate(
                descriptor("ghost", "/x"), Set.of("known")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void rejectsUnknownPlaceholderNamespace() {
        assertThatThrownBy(() -> validator.validate(
                descriptor("known", "/x/{bogus.id}"), Set.of("known")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bogus");
    }

    @Test
    void acceptsValidDescriptor() {
        assertThatCode(() -> validator.validate(
                descriptor("known", "/x/{payload.id}/{vars.q}/{subject.level}/{now}"), Set.of("known")))
                .doesNotThrowAnyException();
    }

    @Test
    void skipsEndpointCheckWhenNamesNull() {
        assertThatCode(() -> validator.validate(descriptor("any", "/x"), null))
                .doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-config-svc test -Dtest=ConnectorSafetyValidatorTest`
Expected: FAIL（`ConnectorSafetyValidator` 不存在 / 编译失败）

- [ ] **Step 3: 写校验器**

`ConnectorSafetyValidator.java`：
```java
package com.sstlfsj.rule.config.internal.publish;

import com.sstlfsj.rule.config.api.connector.ConnectorDescriptor;
import com.sstlfsj.rule.config.api.connector.TemplateParam;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 连接器写时安全校验：endpointRef 已注册、占位符命名空间合法。
 * 资源名集合为 null 时跳过 endpoint 校验（纯 config 部署无 eval catalog，照 MetricSafetyValidator 容错）。
 */
class ConnectorSafetyValidator {

    private static final Pattern PH = Pattern.compile("\\{([a-zA-Z_][\\w.]*)}");
    private static final Set<String> NAMESPACES =
            Set.of("payload", "params", "vars", "subject", "now", "subjectId", "tenantId");

    void validate(ConnectorDescriptor d, Set<String> endpointNames) {
        if (endpointNames != null && !endpointNames.contains(d.endpointRef())) {
            throw new IllegalArgumentException("未注册的 endpointRef: " + d.endpointRef());
        }
        checkPlaceholders(d.request().pathTemplate());
        if (d.request().bodyTemplate() != null) checkPlaceholders(d.request().bodyTemplate());
        for (TemplateParam p : d.request().query()) checkPlaceholders(p.valueTemplate());
        for (TemplateParam p : d.request().headers()) checkPlaceholders(p.valueTemplate());
    }

    private void checkPlaceholders(String template) {
        Matcher m = PH.matcher(template);
        while (m.find()) {
            String token = m.group(1);
            String ns = token.contains(".") ? token.substring(0, token.indexOf('.')) : token;
            if (!NAMESPACES.contains(ns)) {
                throw new IllegalArgumentException("非法占位符命名空间: " + token);
            }
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-config-svc test -Dtest=ConnectorSafetyValidatorTest`
Expected: PASS（4 个测试绿）

- [ ] **Step 5: Commit**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/ConnectorSafetyValidator.java \
        rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/ConnectorSafetyValidatorTest.java
git commit -m "feat(config): 连接器写时安全校验器"
```

---

## Task 7: 写服务实现（校验 + 落库 + 审计 + 失效事件）

**Files:**
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/ConnectorWriteServiceImpl.java`
- Test: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/service/ConnectorWriteServiceImplTest.java`

参考：`rule-config-svc/.../internal/service/MetricWriteServiceImpl.java`（`@Service @Transactional @RequiredArgsConstructor`，写后 `eventPublisher.publishEvent(new OperationAuditedEvent(...))`，targetType 字符串）。审计事件类与构造签名照 `MetricWriteServiceImpl` 中 `OperationAuditedEvent` 的实际用法（执行时打开该文件确认参数顺序）。endpoint 资源名来自可选 SPI `MetricResourceCatalog`（注入 `@Autowired(required=false)` 或 `ObjectProvider`，照 `PublishService` 对 catalog 的可选注入方式）。

- [ ] **Step 1: 写失败测试（Mockito 验证落库 + 两类事件）**

`ConnectorWriteServiceImplTest.java`：
```java
package com.sstlfsj.rule.config.internal.service;

import com.sstlfsj.rule.config.api.event.ConnectorChangedEvent;
import com.sstlfsj.rule.config.api.service.ConnectorWriteService.ConnectorWriteCommand;
import com.sstlfsj.rule.config.internal.domain.ConnectorDefinition;
import com.sstlfsj.rule.config.internal.domain.ConnectorStatus;
import com.sstlfsj.rule.config.api.connector.*;
import com.sstlfsj.rule.config.internal.repository.ConnectorDefinitionMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ConnectorWriteServiceImplTest {

    private final ConnectorDefinitionMapper mapper = mock(ConnectorDefinitionMapper.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    // MetricResourceCatalog 资源名 SPI：测试传 null/桩，使 endpoint 校验跳过或通过

    private ConnectorWriteCommand cmd() {
        return new ConnectorWriteCommand("风控打分", ConnectorDescriptor.builder()
                .endpointRef("risk")
                .request(HttpRequestTemplate.builder()
                        .method(HttpMethod.GET).pathTemplate("/s/{payload.id}")
                        .query(List.of()).headers(List.of()).bodyTemplate(null).build())
                .response(new ResponseMapping(new Predicate("ok", CompareOp.EQ, true), "v"))
                .auth(new StaticHeaderAuth("X-Key", "k"))
                .resilience(ResiliencePolicy.builder()
                        .connectTimeoutMs(200).readTimeoutMs(300).retries(0)
                        .retryOn(Set.of()).circuitBreaker(null).build())
                .errorMapping(List.of()).build());
    }

    @Test
    void createInsertsActiveAndPublishesChangedEvent() {
        ConnectorWriteServiceImpl svc = newServiceWithEndpoint("risk");
        when(mapper.findByCode(1L, "risk-svc")).thenReturn(null);

        svc.create(1L, "risk-svc", cmd(), "u1");

        ArgumentCaptor<ConnectorDefinition> saved = ArgumentCaptor.forClass(ConnectorDefinition.class);
        verify(mapper).insert(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ConnectorStatus.ACTIVE);
        assertThat(saved.getValue().getConnectorCode()).isEqualTo("risk-svc");

        verify(events).publishEvent(new ConnectorChangedEvent("1", "risk-svc"));
        verify(events).publishEvent(argThat(e -> e.getClass().getSimpleName().contains("Audited")));
    }

    @Test
    void createRejectsDuplicateCode() {
        ConnectorWriteServiceImpl svc = newServiceWithEndpoint("risk");
        when(mapper.findByCode(1L, "risk-svc")).thenReturn(new ConnectorDefinition());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> svc.create(1L, "risk-svc", cmd(), "u1"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(mapper, never()).insert(any());
    }

    // 工厂：构造 impl，注入 endpoint 名集合 {endpoint} 使 ConnectorSafetyValidator 通过
    private ConnectorWriteServiceImpl newServiceWithEndpoint(String endpoint) {
        return new ConnectorWriteServiceImpl(mapper, events, () -> Set.of(endpoint));
    }
}
```

> 注：`ConnectorWriteServiceImpl` 第 3 个构造参数为"endpoint 名供给"（`Supplier<Set<String>>` 或可选 `MetricResourceCatalog`）。下一步实现要与此测试一致——若用 `MetricResourceCatalog`，把测试工厂改成传桩 catalog 并 stub `endpointNames()`。实现与测试二选一对齐即可，**保持构造签名一致**。

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-config-svc test -Dtest=ConnectorWriteServiceImplTest`
Expected: FAIL（`ConnectorWriteServiceImpl` 不存在）

- [ ] **Step 3: 写实现**

先打开 `MetricWriteServiceImpl.java` 确认 `OperationAuditedEvent` 构造签名与 catalog 注入方式，照搬。`ConnectorWriteServiceImpl.java`：
```java
package com.sstlfsj.rule.config.internal.service;

import com.sstlfsj.rule.config.api.event.ConnectorChangedEvent;
import com.sstlfsj.rule.config.api.service.ConnectorWriteService;
import com.sstlfsj.rule.config.internal.domain.ConnectorDefinition;
import com.sstlfsj.rule.config.internal.domain.ConnectorStatus;
import com.sstlfsj.rule.config.internal.publish.ConnectorSafetyValidator;
import com.sstlfsj.rule.config.internal.repository.ConnectorDefinitionMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** 连接器写服务实现：写时校验、落库、发审计与失效事件（不升版）。 */
@Service
@Transactional
public class ConnectorWriteServiceImpl implements ConnectorWriteService {

    private final ConnectorDefinitionMapper mapper;
    private final ApplicationEventPublisher eventPublisher;
    private final Supplier<Set<String>> endpointNames;
    private final ConnectorSafetyValidator validator = new ConnectorSafetyValidator();

    /**
     * @param mapper         连接器 mapper
     * @param eventPublisher Spring 事件发布器
     * @param endpointNames  已注册 endpoint 名供给（纯 config 部署返回 null/空 → 跳过 endpoint 校验）
     */
    public ConnectorWriteServiceImpl(ConnectorDefinitionMapper mapper,
                                     ApplicationEventPublisher eventPublisher,
                                     Supplier<Set<String>> endpointNames) {
        this.mapper = mapper;
        this.eventPublisher = eventPublisher;
        this.endpointNames = endpointNames;
    }

    @Override
    public Long create(Long tenantId, String connectorCode, ConnectorWriteCommand cmd, String actorId) {
        if (mapper.findByCode(tenantId, connectorCode) != null) {
            throw new IllegalArgumentException("连接器已存在: " + connectorCode);
        }
        validator.validate(cmd.descriptor(), endpointNames.get());
        ConnectorDefinition c = new ConnectorDefinition();
        c.setTenantId(tenantId);
        c.setConnectorCode(connectorCode);
        c.setName(cmd.name());
        c.setDescriptor(cmd.descriptor());
        c.setStatus(ConnectorStatus.ACTIVE);
        c.setCreatedBy(actorId);
        c.setUpdatedBy(actorId);
        mapper.insert(c);
        publishAudit(tenantId, actorId, "CREATE", connectorCode, c.getId());
        eventPublisher.publishEvent(new ConnectorChangedEvent(String.valueOf(tenantId), connectorCode));
        return c.getId();
    }

    @Override
    public int update(Long tenantId, String connectorCode, ConnectorWriteCommand cmd, String actorId) {
        ConnectorDefinition existing = mapper.findByCode(tenantId, connectorCode);
        if (existing == null) throw new IllegalArgumentException("连接器不存在: " + connectorCode);
        validator.validate(cmd.descriptor(), endpointNames.get());
        existing.setName(cmd.name());
        existing.setDescriptor(cmd.descriptor());
        existing.setUpdatedBy(actorId);
        int n = mapper.updateById(existing);
        publishAudit(tenantId, actorId, "UPDATE", connectorCode, existing.getId());
        eventPublisher.publishEvent(new ConnectorChangedEvent(String.valueOf(tenantId), connectorCode));
        return n;
    }

    @Override
    public List<ConnectorView> listActive(Long tenantId) {
        return mapper.findActiveByTenant(tenantId).stream()
                .map(c -> new ConnectorView(c.getConnectorCode(), c.getName(), c.getStatus().name()))
                .toList();
    }

    // 审计事件构造照 MetricWriteServiceImpl 的 OperationAuditedEvent 实际签名（执行时核对参数顺序）
    private void publishAudit(Long tenantId, String actorId, String action, String code, Long id) {
        // eventPublisher.publishEvent(new OperationAuditedEvent(... targetType="connector_definition" ...));
    }
}
```

> 实现 `publishAudit` 时打开 `MetricWriteServiceImpl` 复制 `OperationAuditedEvent` 的真实构造（含 before/after 快照、actorType、时间戳参数），targetType 用 `"connector_definition"`。测试里对审计事件只断言类型含 "Audited"，故签名细节不影响测试通过，但**必须真发**。

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-config-svc test -Dtest=ConnectorWriteServiceImplTest`
Expected: PASS

- [ ] **Step 5: 跑 config-svc 全量（带 -am，因下游无 kernel 改动但确保整模块绿）**

Run: `$MVN -pl rule-config-svc -am test`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/ConnectorWriteServiceImpl.java \
        rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/service/ConnectorWriteServiceImplTest.java
git commit -m "feat(config): 连接器写服务实现（校验+落库+审计+失效事件）"
```

---

## Task 8: rule-api 连接器 controller（list/create/update）

**Files:**
- Create: `rule-api/src/main/java/com/sstlfsj/rule/web/admin/ConnectorController.java`
- Create: `rule-api/src/main/java/com/sstlfsj/rule/web/admin/dto/ConnectorRequest.java`
- Create: `rule-api/src/main/java/com/sstlfsj/rule/web/admin/dto/ConnectorResponse.java`
- Test: `rule-api/src/test/java/com/sstlfsj/rule/web/admin/ConnectorControllerTest.java`

参考：`rule-api/.../web/admin/MetricController.java`（`@RestController @RequestMapping("/admin/v1/metrics") @RequiredArgsConstructor`，`@RequestParam String tenantId`、`@RequestHeader("X-Actor-Id")`、`ApiResponse.ok(...)`、POST `@ResponseStatus(CREATED)`，controller 内 `Long.valueOf(tenantId)` 适配 service 的 Long）。controller 测范式照 `MetricControllerTest`（`@WebMvcTest` 或 `MockMvc` standalone，mock service）。

- [ ] **Step 1: 写请求/响应 DTO**

`ConnectorRequest.java`：
```java
package com.sstlfsj.rule.web.admin.dto;

import com.sstlfsj.rule.config.api.connector.ConnectorDescriptor;

/**
 * 连接器写请求体。
 *
 * @param name       展示名
 * @param descriptor 连接器描述符（typed，前端按 JSON Schema 构造）
 */
public record ConnectorRequest(String name, ConnectorDescriptor descriptor) {}
```

`ConnectorResponse.java`：
```java
package com.sstlfsj.rule.web.admin.dto;

/**
 * 连接器列表项响应。
 *
 * @param connectorCode 编码
 * @param name          名称
 * @param status        状态
 */
public record ConnectorResponse(String connectorCode, String name, String status) {}
```

- [ ] **Step 2: 写 controller 失败测试**

`ConnectorControllerTest.java`（照 `MetricControllerTest` 的启动方式；下示意核心断言）：
```java
// 类注解 + MockMvc 注入照 MetricControllerTest；mock ConnectorWriteService service。
@Test
void createReturns201AndId() {
    when(service.create(eq(1L), eq("risk-svc"), any(), eq("u1"))).thenReturn(99L);

    mockMvc.perform(post("/admin/v1/connectors")
                    .param("tenantId", "1")
                    .param("connectorCode", "risk-svc")
                    .header("X-Actor-Id", "u1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"name":"风控打分","descriptor":{
                          "endpointRef":"risk",
                          "request":{"method":"GET","pathTemplate":"/s/{payload.id}","query":[],"headers":[],"bodyTemplate":null},
                          "response":{"successWhen":{"path":"ok","op":"EQ","value":true},"valuePath":"v"},
                          "auth":{"kind":"STATIC_HEADER","headerName":"X-Key","credentialRef":"k"},
                          "resilience":{"connectTimeoutMs":200,"readTimeoutMs":300,"retries":0,"retryOn":[],"circuitBreaker":null},
                          "errorMapping":[]}}"""))
            .andExpect(status().isCreated());

    verify(service).create(eq(1L), eq("risk-svc"), any(), eq("u1"));
}

@Test
void listReturnsActiveConnectors() {
    when(service.listActive(1L)).thenReturn(List.of(
            new ConnectorWriteService.ConnectorView("risk-svc", "风控打分", "ACTIVE")));

    mockMvc.perform(get("/admin/v1/connectors").param("tenantId", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].connectorCode").value("risk-svc"));
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `$MVN -pl rule-api test -Dtest=ConnectorControllerTest`
Expected: FAIL（`ConnectorController` 不存在）

- [ ] **Step 4: 写 controller + MapStruct convert**

`convert/ConnectorConvert.java`（照 `SceneConvert`）：
```java
package com.sstlfsj.rule.web.admin.convert;

import com.sstlfsj.rule.config.api.service.ConnectorWriteService.ConnectorView;
import com.sstlfsj.rule.web.admin.dto.ConnectorResponse;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

/** 连接器视图 → API 响应 DTO 转换。 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface ConnectorConvert {
    ConnectorResponse toResponse(ConnectorView view);
}
```

`ConnectorController.java`：
```java
package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.config.api.service.ConnectorWriteService;
import com.sstlfsj.rule.config.api.service.ConnectorWriteService.ConnectorWriteCommand;
import com.sstlfsj.rule.web.admin.convert.ConnectorConvert;
import com.sstlfsj.rule.web.admin.dto.ConnectorRequest;
import com.sstlfsj.rule.web.admin.dto.ConnectorResponse;
// ApiResponse 导入照 MetricController 实际包路径
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 连接器写 API（list/create/update；:test 见 P3）。 */
@RestController
@RequestMapping("/admin/v1/connectors")
@RequiredArgsConstructor
public class ConnectorController {

    private final ConnectorWriteService service;
    private final ConnectorConvert convert;

    @GetMapping
    public ApiResponse<List<ConnectorResponse>> list(@RequestParam String tenantId) {
        List<ConnectorResponse> data = service.listActive(Long.valueOf(tenantId)).stream()
                .map(convert::toResponse).toList();
        return ApiResponse.ok(data);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Long> create(@RequestParam String tenantId,
                                    @RequestParam String connectorCode,
                                    @RequestHeader("X-Actor-Id") String actorId,
                                    @RequestBody ConnectorRequest req) {
        Long id = service.create(Long.valueOf(tenantId), connectorCode,
                new ConnectorWriteCommand(req.name(), req.descriptor()), actorId);
        return ApiResponse.ok(id);
    }

    @PutMapping("/{connectorCode}")
    public ApiResponse<Integer> update(@PathVariable String connectorCode,
                                       @RequestParam String tenantId,
                                       @RequestHeader("X-Actor-Id") String actorId,
                                       @RequestBody ConnectorRequest req) {
        int n = service.update(Long.valueOf(tenantId), connectorCode,
                new ConnectorWriteCommand(req.name(), req.descriptor()), actorId);
        return ApiResponse.ok(n);
    }
}
```

> 打开 `MetricController` 确认 `ApiResponse` 的精确包与 `ok(...)` 用法，import 对齐。

- [ ] **Step 5: 跑测试确认通过**

Run: `$MVN -pl rule-api test -Dtest=ConnectorControllerTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add rule-api/src/main/java/com/sstlfsj/rule/web/admin/ConnectorController.java \
        rule-api/src/main/java/com/sstlfsj/rule/web/admin/dto/ConnectorRequest.java \
        rule-api/src/main/java/com/sstlfsj/rule/web/admin/dto/ConnectorResponse.java \
        rule-api/src/main/java/com/sstlfsj/rule/web/admin/convert/ConnectorConvert.java \
        rule-api/src/test/java/com/sstlfsj/rule/web/admin/ConnectorControllerTest.java
git commit -m "feat(api): 连接器写 controller（list/create/update）"
```

---

## Task 9: 全量兜底 + Modulith 边界检查

**Files:** 无新增

- [ ] **Step 1: 全量 clean test（强制重编译所有 test 类，抓过期）**

Run: `$MVN clean test`
Expected: BUILD SUCCESS（含 config-svc / rule-api / 及 Modulith ArchUnit/边界测试全绿）

- [ ] **Step 2: 确认 Modulith 边界绿**

descriptor 套件已置于公开包 `config/api/connector/`（与 `config.api.service.ConnectorWriteService`、`config.api.event.ConnectorChangedEvent` 同为 api 公开契约），rule-api 与（P2 的）eval-svc 均可合法引用。`ConnectorDefinition`（internal 实体）引 api 包描述符合法。若 ArchUnit 仍报 rule-api → config 违规，照 `MetricController` → `config.api.service.MetricWriteService` 的既有合法依赖范式核对。**预期无需移动。**

- [ ] **Step 3: 无改动则跳过；有微调则 commit**

```bash
git add -A
git commit -m "fix(config): 连接器 Modulith 边界对齐"
```

---

## Self-Review 记录（写计划时自检）

- **Spec 覆盖**：本计划覆盖设计 §3 契约层 Connector 的"写/校验/存储"、§5 描述符模型、§12 决策 #1(可复用命名资源)/#2(可变不冻结)。§6 错误细码、§8 SQL、§9 测试端点/conformance、§4 eval 解析/失效、演进位均在 P2–P4。
- **类型一致**：`ConnectorWriteCommand(name, descriptor)`、`ConnectorView(connectorCode,name,status)`、`ConnectorChangedEvent(tenantId:String, connectorCode:String)`、`ConnectorSafetyValidator.validate(descriptor, Set<String>)`、`ConnectorWriteServiceImpl(mapper, eventPublisher, Supplier<Set<String>>)` 全计划一致。
- **占位符**：审计事件构造、controller 的 `ApiResponse`/mockMvc 启动方式、mapper 集成测试基类——均明确指向具体 exemplar 文件要求执行者打开核对，非"自行处理"。
- **风险**：Modulith 边界（Task 9 给了归位预案）；Jackson3 包名 `tools.jackson`（Task 2 Step 6 提示）；`autoResultMap` 未生效红旗（Task 4 Step 3）。
