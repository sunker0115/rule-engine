# rule-example 模块实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新建 `rule-example` 纯测试模块，以 4 个完整业务场景验证端到端链路。

**Architecture:** `rule-example` 依赖 `rule-app`，无 main sources，通过 `@SpringBootTest` 启动完整服务上下文 + Testcontainers MySQL + WireMock。每个 Scenario 建模一个业务故事，基建覆盖随之自然达成。

**Tech Stack:** Spring Boot 4.0.6 / Testcontainers (MySQL 8.0) / WireMock Standalone / Maven Failsafe / MyBatis-Plus / Flyway

**Design Doc:** `docs/superpowers/specs/2026-06-11-rule-example-module-design.md`

**测试规范（铁律）：场景即业务故事。** 禁止为验证 handler/infrastructure 而独立建纯技术场景。每个 Scenario 对应一个业务上可理解的完整用例，基建覆盖自然随之达成。

---

### Task 1: Module Scaffolding

**Files:**
- Create: `rule-example/pom.xml`
- Modify: `pom.xml` (parent, add examples profile)
- Create: `rule-example/src/test/resources/application-test.yml`
- Create: `rule-example/src/test/resources/sql/V1__business_tables.sql`

- [ ] **Step 1: 创建 rule-example/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.sstlfsj.rule</groupId>
        <artifactId>rule-engine</artifactId>
        <version>${revision}</version>
    </parent>
    <artifactId>rule-example</artifactId>
    <packaging>jar</packaging>

    <dependencies>
        <dependency>
            <groupId>com.sstlfsj.rule</groupId>
            <artifactId>rule-app</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>mysql</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-testcontainers</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.wiremock</groupId>
            <artifactId>wiremock-standalone</artifactId>
            <version>3.12.1</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-flyway</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-mysql</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.awaitility</groupId>
            <artifactId>awaitility</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-failsafe-plugin</artifactId>
                <configuration>
                    <includes>
                        <include>**/*Scenario.java</include>
                    </includes>
                </configuration>
                <executions>
                    <execution>
                        <goals>
                            <goal>integration-test</goal>
                            <goal>verify</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: 在父 pom.xml 添加 examples profile**

```xml
<profiles>
    <profile>
        <id>examples</id>
        <modules>
            <module>rule-example</module>
        </modules>
    </profile>
</profiles>
```

- [ ] **Step 3: 创建 application-test.yml**

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration,classpath:sql
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver

engine:
  rule:
    metric:
      source:
        business-db:
          type: MYSQL
```

- [ ] **Step 4: 创建业务表 DDL**

```sql
CREATE TABLE IF NOT EXISTS orders (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    VARCHAR(64)  NOT NULL COMMENT '用户 ID',
    amount     DECIMAL(12,2) NOT NULL COMMENT '订单金额',
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表(业务示例)';
```

- [ ] **Step 5: 验证模块编译**

```bash
$MVN compile -Pexamples -pl rule-example
```

- [ ] **Step 6: 提交**

```bash
git add rule-example/pom.xml rule-example/src/test/resources/ pom.xml
git commit -m "feat(rule-example): add module scaffolding with pom, config, business DDL"
```

---

### Task 2: ScenarioSupport 基类

**Files:**
- Create: `rule-example/src/test/java/com/sstlfsj/rule/example/ScenarioSupport.java`

- [ ] **Step 1: 创建 ScenarioSupport.java**

```java
package com.sstlfsj.rule.example;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.sstlfsj.rule.RuleEngineApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

@SpringBootTest(
        classes = RuleEngineApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
public abstract class ScenarioSupport {

    static final String TENANT_ID = "9001";
    static final String TENANT_CODE = "example";
    static final String ACTOR_ID = "scenario-runner";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("rule-engine-example");

    static final WireMockServer WIREMOCK = new WireMockServer(
            WireMockConfiguration.options().dynamicPort());

    @LocalServerPort
    int localPort;

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected JdbcTemplate jdbc;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("engine.rule.metric.source.business-db.url", MYSQL::getJdbcUrl);
        registry.add("engine.rule.metric.source.business-db.username", MYSQL::getUsername);
        registry.add("engine.rule.metric.source.business-db.password", MYSQL::getPassword);
        registry.add("engine.rule.metric.source.mock-api.url",
                () -> "http://localhost:" + WIREMOCK.port());
        registry.add("engine.rule.action.send-alert.url",
                () -> "http://localhost:" + WIREMOCK.port() + "/webhook/alert");
        registry.add("engine.rule.action.send-alert.timeout-ms", () -> "2000");
    }

    @BeforeAll
    static void startWireMock() {
        WIREMOCK.start();
    }

    @AfterAll
    static void stopWireMock() {
        WIREMOCK.stop();
    }

    @BeforeEach
    void seedTenant() {
        jdbc.update("INSERT IGNORE INTO tenant (id, code, name) VALUES (?, ?, ?)",
                Long.parseLong(TENANT_ID), TENANT_CODE, "示例租户");
        WIREMOCK.resetAll();
    }

    @AfterEach
    void truncateAll() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        for (String table : ALL_TABLES) {
            jdbc.execute("TRUNCATE TABLE " + table);
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
    }

    private static final String[] ALL_TABLES = {
            "action_execution", "dry_run_action_execution",
            "node_trace", "dry_run_node_trace",
            "evaluation_session", "dry_run_session",
            "job_execution",
            "rule_version", "rule_definition",
            "scene", "scene_version",
            "metric_definition",
            "decision_definition",
            "audit_log",
            "job_definition",
            "orders"
    };

    // ---- 便捷方法 ----

    protected String adminUrl(String path) {
        return "http://localhost:" + localPort + "/admin/v1" + path;
    }

    protected String apiUrl(String path) {
        return "http://localhost:" + localPort + "/api/v1" + path;
    }

    protected Long createScene(String sceneCode, String name, String dominantMode,
                                String subjectType, List<String> eventTypes) {
        var body = Map.of(
                "tenantId", TENANT_ID, "sceneCode", sceneCode, "name", name,
                "dominantMode", dominantMode, "subjectType", subjectType,
                "eventTypes", eventTypes != null ? eventTypes : List.of(),
                "payloadSchema", List.of(), "defaultParams", Map.of()
        );
        var resp = restTemplate.postForEntity(adminUrl("/scenes"), withActor(body), Map.class);
        return ((Number) resp.getBody().get("data")).longValue();
    }

    protected Long createDecision(String code, String name, int priority,
                                   List<Map<String, Object>> actions) {
        var body = Map.of(
                "code", code, "name", name, "priority", priority,
                "description", "scenario-test",
                "actions", actions != null ? actions : List.of()
        );
        var resp = restTemplate.postForEntity(
                adminUrl("/decisions?tenantId=" + TENANT_ID), withActor(body), Map.class);
        return ((Number) resp.getBody().get("data")).longValue();
    }

    protected Long createMetric(String metricCode, String name, String sourceType,
                                 String dataType, Map<String, Object> params,
                                 int cacheTtlSeconds, boolean allowProvided) {
        var body = Map.of(
                "name", name, "sourceType", sourceType, "dataType", dataType,
                "params", params != null ? params : Map.of(),
                "cacheTtlSeconds", cacheTtlSeconds, "allowProvided", allowProvided
        );
        var resp = restTemplate.postForEntity(
                adminUrl("/metrics?tenantId=" + TENANT_ID + "&metricCode=" + metricCode),
                withActor(body), Map.class);
        return ((Number) resp.getBody().get("data")).longValue();
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> createRule(String sceneCode, String code, String name,
                     Object conditionAst, List<Map<String, String>> decisionBindings,
                     List<String> triggerEventTypes, String kind) {
        var body = new java.util.LinkedHashMap<>();
        body.put("tenantId", TENANT_ID);
        body.put("sceneCode", sceneCode);
        body.put("code", code);
        body.put("name", name);
        body.put("conditionAst", conditionAst);
        body.put("decisionBindings", decisionBindings != null ? decisionBindings : List.of());
        body.put("preGates", List.of());
        body.put("triggerEventTypes", triggerEventTypes != null ? triggerEventTypes : List.of());
        body.put("kind", kind);
        var resp = restTemplate.postForEntity(adminUrl("/rules"), withActor(body), Map.class);
        return (Map<String, Object>) resp.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> publishRule(Long ruleId) {
        var resp = restTemplate.postForEntity(
                adminUrl("/rules/" + ruleId + "/publish?tenantId=" + TENANT_ID),
                withActor(null), Map.class);
        return (Map<String, Object>) resp.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> evaluate(String sceneCode, String eventType,
                                            String subjectId, Map<String, Object> payload) {
        var body = Map.of(
                "tenantCode", TENANT_CODE, "sceneCode", sceneCode,
                "eventType", eventType, "subjectId", subjectId,
                "eventId", java.util.UUID.randomUUID().toString(),
                "occurredAt", java.time.Instant.now().toString(),
                "payload", payload != null ? payload : Map.of()
        );
        var resp = restTemplate.postForEntity(apiUrl("/rule/evaluate"), body, Map.class);
        return (Map<String, Object>) resp.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> pushEvent(String sceneCode, String eventType,
                                             String subjectId, Map<String, Object> payload) {
        var body = Map.of(
                "tenantCode", TENANT_CODE, "sceneCode", sceneCode,
                "eventType", eventType, "subjectId", subjectId,
                "eventId", java.util.UUID.randomUUID().toString(),
                "occurredAt", java.time.Instant.now().toString(),
                "payload", payload != null ? payload : Map.of()
        );
        var resp = restTemplate.postForEntity(apiUrl("/rule/event"), body, Map.class);
        return (Map<String, Object>) resp.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> dryRun(Long ruleVersionId, Long ruleId,
                                          String sceneCode, String eventType,
                                          String subjectId, Map<String, Object> payload) {
        var body = Map.of(
                "tenantCode", TENANT_CODE, "sceneCode", sceneCode,
                "eventType", eventType, "subjectId", subjectId,
                "eventId", java.util.UUID.randomUUID().toString(),
                "occurredAt", java.time.Instant.now().toString(),
                "payload", payload != null ? payload : Map.of()
        );
        String url = apiUrl("/rule/dry-run");
        if (ruleVersionId != null) url += "?ruleVersionId=" + ruleVersionId;
        else if (ruleId != null) url += "?ruleId=" + ruleId;
        var resp = restTemplate.postForEntity(url, body, Map.class);
        return (Map<String, Object>) resp.getBody().get("data");
    }

    protected int countRows(String table) {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return c != null ? c : 0;
    }

    protected List<Map<String, Object>> query(String sql, Object... args) {
        return jdbc.queryForList(sql, args);
    }

    private HttpEntity<Object> withActor(Object body) {
        var headers = new HttpHeaders();
        headers.set("X-Actor-Id", ACTOR_ID);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}
```

- [ ] **Step 2: 验证编译通过**

```bash
$MVN test-compile -Pexamples -pl rule-example
```

- [ ] **Step 3: 提交**

```bash
git add rule-example/src/test/java/com/sstlfsj/rule/example/ScenarioSupport.java
git commit -m "feat(rule-example): add ScenarioSupport base class with Testcontainers + WireMock"
```

---

### Task 3: HighRiskLoginScenario — 异常登录检测

**Files:**
- Create: `rule-example/src/test/java/com/sstlfsj/rule/example/scenario/HighRiskLoginScenario.java`

**业务故事：** 同一用户从新 IP 短时间多次登录 → 命中高风险规则 → 异步告警到安全团队 webhook。

**覆盖：** PULL evaluate + PUSH event + dry-run 两种入口 + WireMock webhook 验证 + action_execution 落库（SUCCESS/FAILED）。

- [ ] **Step 1: 创建 HighRiskLoginScenario.java**

```java
package com.sstlfsj.rule.example.scenario;

import com.sstlfsj.rule.example.ScenarioSupport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** 异常登录检测：从新 IP 高频登录 → 命中高风险规则 → 异步告警到安全团队 webhook。 */
class HighRiskLoginScenario extends ScenarioSupport {

    private Map<String, Object> alwaysTrueAst() {
        return Map.of("type", "AND", "children", List.of());
    }

    // ---- PULL evaluate + webhook 通知 ----

    @Test
    void abnormalLogin_pullEval_hitAndAlertWebhook() {
        stubFor(post(urlPathEqualTo("/webhook/alert"))
                .willReturn(aResponse().withStatus(200)));

        // 准备：配置风控规则
        createScene("login-anti", "反欺诈登录检测", "PUSH", "USER", List.of("login"));
        createDecision("SEC_ALERT", "安全告警", 100,
                List.of(Map.of("actionId", "a1", "actionType", "SEND_ALERT",
                        "sortOrder", 0, "params", Map.of("level", "critical"))));

        Map<String, Object> rule = createRule("login-anti", "high-risk-login", "高风险登录",
                alwaysTrueAst(),
                List.of(Map.of("decisionCode", "SEC_ALERT")),
                List.of("login"), "AST_BOOLEAN");
        long ruleId = ((Number) rule.get("ruleDefinitionId")).longValue();
        long versionId = ((Number) rule.get("ruleVersionId")).longValue();
        publishRule(ruleId);

        // 执行：模拟异常登录
        Map<String, Object> result = evaluate("login-anti", "login", "u1",
                Map.of("userId", "u1", "ip", "192.168.99.1"));

        // 验证：命中 + 告警已发送
        assertThat(result.get("ruleHit")).isEqualTo(true);
        assertThat(countRows("evaluation_session")).isEqualTo(1);
        assertThat(countRows("action_execution")).isEqualTo(1);

        List<Map<String, Object>> actions = query(
                "SELECT action_type, status FROM action_execution");
        assertThat(actions.get(0).get("status").toString()).isEqualTo("SUCCESS");

        // WireMock 收到 webhook
        verify(postRequestedFor(urlPathEqualTo("/webhook/alert")));
    }

    // ---- dry-run（两种入口） ----

    @Test
    void abnormalLogin_dryRun_byVersionId() {
        createScene("login-dry", "登录风控(Dry)", "PUSH", "USER", List.of("login"));
        createDecision("REVIEW", "人工审核", 10, List.of());

        Map<String, Object> rule = createRule("login-dry", "dry-rule", "规则(Dry)",
                alwaysTrueAst(),
                List.of(Map.of("decisionCode", "REVIEW")),
                List.of("login"), "AST_BOOLEAN");
        long ruleId = ((Number) rule.get("ruleDefinitionId")).longValue();
        long versionId = ((Number) rule.get("ruleVersionId")).longValue();
        publishRule(ruleId);

        // dry-run 按精确版本
        Map<String, Object> result = dryRun(versionId, null, "login-dry", "login", "u1", Map.of());

        assertThat(result.get("ruleHit")).isEqualTo(true);
        assertThat(countRows("dry_run_session")).isEqualTo(1);
        assertThat(countRows("dry_run_node_trace")).isGreaterThan(0);
        // dry-run 不产生副作用
        assertThat(countRows("evaluation_session")).isEqualTo(0);
        assertThat(countRows("action_execution")).isEqualTo(0);
    }

    @Test
    void abnormalLogin_dryRun_byRuleId_draftVersion() {
        createScene("login-dry2", "登录风控(Dry2)", "PULL", "USER", List.of("login"));
        createDecision("BLOCK", "拦截", 100, List.of());

        // 建草稿但不发布
        Map<String, Object> rule = createRule("login-dry2", "draft-rule2", "草稿规则",
                alwaysTrueAst(),
                List.of(Map.of("decisionCode", "BLOCK")),
                List.of("login"), "AST_BOOLEAN");
        long ruleId = ((Number) rule.get("ruleDefinitionId")).longValue();

        // dry-run 按 ruleId → 应取到最新 DRAFT
        Map<String, Object> result = dryRun(null, ruleId, "login-dry2", "login", "u1", Map.of());

        assertThat(result.get("ruleHit")).isEqualTo(true);
        assertThat(countRows("dry_run_session")).isEqualTo(1);
        assertThat(countRows("evaluation_session")).isEqualTo(0);
    }

    // ---- PUSH 异步事件 ----

    @Test
    void abnormalLogin_pushEvent_asyncAlertDelivered() {
        stubFor(post(urlPathEqualTo("/webhook/alert"))
                .willReturn(aResponse().withStatus(200)));

        createScene("login-push", "登录风控(Push)", "PUSH", "USER", List.of("login"));
        createDecision("SEC_ALERT", "安全告警", 100,
                List.of(Map.of("actionId", "a1", "actionType", "SEND_ALERT",
                        "sortOrder", 0, "params", Map.of("level", "high"))));

        Map<String, Object> rule = createRule("login-push", "push-alert", "推送告警",
                alwaysTrueAst(),
                List.of(Map.of("decisionCode", "SEC_ALERT")),
                List.of("login"), "AST_BOOLEAN");
        publishRule(((Number) rule.get("ruleDefinitionId")).longValue());

        // 执行：PUSH 异步
        Map<String, Object> pushResp = pushEvent("login-push", "login", "u2",
                Map.of("userId", "u2", "ip", "10.10.10.10"));
        assertThat(pushResp.get("accepted")).isEqualTo(true);

        // 等待异步落库
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(countRows("evaluation_session")).isEqualTo(1);
        });

        List<Map<String, Object>> sessions = query(
                "SELECT status, mode FROM evaluation_session");
        assertThat(sessions.get(0).get("status").toString()).isEqualTo("HIT");
        assertThat(sessions.get(0).get("mode").toString()).isEqualTo("PUSH");

        // webhook 已投递
        verify(postRequestedFor(urlPathEqualTo("/webhook/alert")));
    }

    // ---- webhook 失败：非 2xx → action_execution=FAILED ----

    @Test
    void abnormalLogin_webhookFails_actionRecordedFailed() {
        WIREMOCK.resetAll();
        stubFor(post(urlPathEqualTo("/webhook/alert"))
                .willReturn(aResponse().withStatus(500)));

        createScene("login-fail", "登录风控(Fail)", "PUSH", "USER", List.of("login"));
        createDecision("SEC_ALERT", "安全告警", 100,
                List.of(Map.of("actionId", "a1", "actionType", "SEND_ALERT",
                        "sortOrder", 0, "params", Map.of())));

        Map<String, Object> rule = createRule("login-fail", "fail-alert", "失败告警",
                alwaysTrueAst(),
                List.of(Map.of("decisionCode", "SEC_ALERT")),
                List.of("login"), "AST_BOOLEAN");
        publishRule(((Number) rule.get("ruleDefinitionId")).longValue());

        evaluate("login-fail", "login", "u1", Map.of("userId", "u1"));

        List<Map<String, Object>> actions = query(
                "SELECT status FROM action_execution");
        assertThat(actions.get(0).get("status").toString()).isEqualTo("FAILED");
    }
}
```

- [ ] **Step 2: 运行测试**

```bash
$MVN verify -Pexamples -pl rule-example -Dtest=HighRiskLoginScenario
```

- [ ] **Step 3: 提交**

```bash
git add rule-example/src/test/java/com/sstlfsj/rule/example/scenario/
git commit -m "feat(rule-example): add HighRiskLoginScenario — 异常登录检测全链路"
```

---

### Task 4: OrderFraudScenario — 订单风控

**Files:**
- Create: `rule-example/src/test/java/com/sstlfsj/rule/example/scenario/OrderFraudScenario.java`

**业务故事：** 当日订单总金额超 10000 → 拦截，不足 → 放行。

**覆盖：** SQL_AGGREGATE 取数（orders 业务表）→ 命中/未命中两端。

- [ ] **Step 1: 创建 OrderFraudScenario.java**

```java
package com.sstlfsj.rule.example.scenario;

import com.sstlfsj.rule.example.ScenarioSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 订单风控：当日订单总金额超阈值 → 拦截。SQL_AGGREGATE 聚合取数。 */
class OrderFraudScenario extends ScenarioSupport {

    @BeforeEach
    void seedOrders() {
        // u1：两笔共 15000（超标）; u2：一笔 500（安全）
        jdbc.update("INSERT INTO orders (user_id, amount) VALUES (?, ?)", "u1", 8000.00);
        jdbc.update("INSERT INTO orders (user_id, amount) VALUES (?, ?)", "u1", 7000.00);
        jdbc.update("INSERT INTO orders (user_id, amount) VALUES (?, ?)", "u2", 500.00);
    }

    @Test
    void dailyOrderAmount_exceedsThreshold_blocked() {
        // 准备
        createScene("order-anti", "订单反欺诈", "PULL", "USER", List.of("order"));
        createDecision("BLOCK", "拦截交易", 100, List.of());

        // SQL_AGGREGATE：当日订单总额
        createMetric("daily-order-sum", "当日订单总额", "SQL_AGGREGATE", "DECIMAL",
                Map.of(
                        "dataSource", "business-db",
                        "sql", "SELECT COALESCE(SUM(amount), 0) FROM orders WHERE user_id = :subjectId AND DATE(created_at) = CURDATE()"
                ), 60, false);

        // 规则：daily-order-sum > 10000 → BLOCK
        Map<String, Object> conditionAst = Map.of(
                "type", "AND",
                "children", List.of(Map.of(
                        "type", "CONDITION",
                        "conditionType", "GT",
                        "metricCode", "daily-order-sum",
                        "params", Map.of("threshold", 10000)
                ))
        );
        Map<String, Object> rule = createRule("order-anti", "daily-limit", "单日限额",
                conditionAst,
                List.of(Map.of("decisionCode", "BLOCK")),
                List.of("order"), "AST_BOOLEAN");
        publishRule(((Number) rule.get("ruleDefinitionId")).longValue());

        // u1：当日订单 15000 > 10000 → HIT
        Map<String, Object> hitResult = evaluate("order-anti", "order", "u1", Map.of());
        assertThat(hitResult.get("ruleHit")).isEqualTo(true);
        Map<String, Object> decision = (Map<String, Object>) hitResult.get("finalDecision");
        assertThat(decision.get("code")).isEqualTo("BLOCK");

        // u2：当日订单 500 < 10000 → MISS
        Map<String, Object> missResult = evaluate("order-anti", "order", "u2", Map.of());
        assertThat(missResult.get("ruleHit")).isEqualTo(false);

        // 落库验证
        assertThat(countRows("evaluation_session")).isEqualTo(2);
        List<Map<String, Object>> sessions = query(
                "SELECT subject_id, status FROM evaluation_session ORDER BY subject_id");
        assertThat(sessions.get(0).get("status").toString()).isEqualTo("HIT");
        assertThat(sessions.get(1).get("status").toString()).isEqualTo("MISS");
    }
}
```

- [ ] **Step 2: 运行测试**

```bash
$MVN verify -Pexamples -pl rule-example -Dtest=OrderFraudScenario
```

- [ ] **Step 3: 提交**

```bash
git add rule-example/src/test/java/com/sstlfsj/rule/example/scenario/OrderFraudScenario.java
git commit -m "feat(rule-example): add OrderFraudScenario — 订单风控 SQL_AGGREGATE 取数"
```

---

### Task 5: CreditEvaluationScenario — 信用评估

**Files:**
- Create: `rule-example/src/test/java/com/sstlfsj/rule/example/scenario/CreditEvaluationScenario.java`

**业务故事：** 贷款申请调用外部信用评分接口 → 评分 < 600 拒贷。

**覆盖：** EXTERNAL_HTTP 取数（WireMock 模拟外部 API）→ JSONPath 提取 → 类型转换。

- [ ] **Step 1: 创建 CreditEvaluationScenario.java**

```java
package com.sstlfsj.rule.example.scenario;

import com.sstlfsj.rule.example.ScenarioSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/** 信用评估：贷款调用外部信用评分接口 → 评分不足 → 拒贷。EXTERNAL_HTTP 取数。 */
class CreditEvaluationScenario extends ScenarioSupport {

    @BeforeEach
    void stubCreditApi() {
        // WireMock 模拟外部信用评分接口
        stubFor(get(urlPathEqualTo("/api/credit/score/u_rich"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":0,\"data\":{\"score\":850,\"level\":\"A\"}}")));
        stubFor(get(urlPathEqualTo("/api/credit/score/u_poor"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":0,\"data\":{\"score\":350,\"level\":\"D\"}}")));
    }

    @Test
    void creditScore_belowThreshold_rejected() {
        // 准备
        createScene("loan", "贷款审批", "PULL", "USER", List.of("apply"));
        createDecision("REJECT", "拒贷", 100, List.of());

        // EXTERNAL_HTTP metric：信用评分
        createMetric("credit-score", "信用评分", "EXTERNAL_HTTP", "LONG",
                Map.of(
                        "dataSource", "mock-api",
                        "urlTemplate", "/api/credit/score/{payload.uid}",
                        "method", "GET",
                        "jsonPath", "data.score"
                ), 120, false);

        // 规则：credit-score < 600 → REJECT（拒贷）
        Map<String, Object> conditionAst = Map.of(
                "type", "AND",
                "children", List.of(Map.of(
                        "type", "CONDITION",
                        "conditionType", "LT",
                        "metricCode", "credit-score",
                        "params", Map.of("threshold", 600)
                ))
        );
        Map<String, Object> rule = createRule("loan", "low-score-reject", "低分拒贷",
                conditionAst,
                List.of(Map.of("decisionCode", "REJECT")),
                List.of("apply"), "AST_BOOLEAN");
        publishRule(((Number) rule.get("ruleDefinitionId")).longValue());

        // 高信用用户（850 分）→ 不命中拒贷规则
        Map<String, Object> goodResult = evaluate("loan", "apply", "user-1",
                Map.of("uid", "u_rich"));
        assertThat(goodResult.get("ruleHit")).isEqualTo(false);

        // 低信用用户（350 分）→ 命中断贷规则
        Map<String, Object> badResult = evaluate("loan", "apply", "user-2",
                Map.of("uid", "u_poor"));
        assertThat(badResult.get("ruleHit")).isEqualTo(true);
        Map<String, Object> decision = (Map<String, Object>) badResult.get("finalDecision");
        assertThat(decision.get("code")).isEqualTo("REJECT");

        // 验证 WireMock 确实被调用了
        verify(getRequestedFor(urlPathEqualTo("/api/credit/score/u_rich")));
        verify(getRequestedFor(urlPathEqualTo("/api/credit/score/u_poor")));

        // 落库验证
        assertThat(countRows("evaluation_session")).isEqualTo(2);
    }
}
```

- [ ] **Step 2: 运行测试**

```bash
$MVN verify -Pexamples -pl rule-example -Dtest=CreditEvaluationScenario
```

- [ ] **Step 3: 提交**

```bash
git add rule-example/src/test/java/com/sstlfsj/rule/example/scenario/CreditEvaluationScenario.java
git commit -m "feat(rule-example): add CreditEvaluationScenario — 信用评估 EXTERNAL_HTTP 取数"
```

---

### Task 6: SdkTradingScenario — SDK 交易风控

**Files:**
- Create: `rule-example/src/test/java/com/sstlfsj/rule/example/scenario/SdkTradingScenario.java`

**业务故事：** 商户端 SDK 嵌入规则引擎 → 定期拉取规则 → 本地评估交易事件。

**覆盖：** SDK 快照端点 + PollingRuleSource 轮询刷新 + RuleEngineClient 嵌入式评估。

- [ ] **Step 1: 创建 SdkTradingScenario.java**

```java
package com.sstlfsj.rule.example.scenario;

import com.sstlfsj.rule.example.ScenarioSupport;
import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.sdk.RuleEngineClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** SDK 交易风控：商户端 SDK 嵌入 → 定期轮询规则 → 本地评估交易。 */
class SdkTradingScenario extends ScenarioSupport {

    private String sdkBaseUrl() {
        return "http://localhost:" + localPort + "/sdk/v1";
    }

    @Test
    void tradingAmountExceedsLimit_sdkPullsAndEvaluatesHit() {
        // 准备：通过 admin API 建风控配置并发布
        createScene("merchant-trade", "商户交易风控", "PULL", "USER", List.of("trade"));
        createDecision("REVIEW", "人工审核", 50, List.of());

        // 规则：交易金额 > 5000 → 人工审核
        Map<String, Object> conditionAst = Map.of(
                "type", "AND",
                "children", List.of(Map.of(
                        "type", "CONDITION",
                        "conditionType", "GT",
                        "metricCode", "amount",
                        "params", Map.of("threshold", 5000)
                ))
        );
        Map<String, Object> rule = createRule("merchant-trade", "large-trade", "大额交易",
                conditionAst,
                List.of(Map.of("decisionCode", "REVIEW")),
                List.of("trade"), "AST_BOOLEAN");
        publishRule(((Number) rule.get("ruleDefinitionId")).longValue());

        // 验证 SDK 端点可访问
        var snapshotsResp = restTemplate.getForEntity(
                sdkBaseUrl() + "/snapshots?tenantId=" + TENANT_ID, List.class);
        assertThat(snapshotsResp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(snapshotsResp.getBody()).isNotNull();

        // SDK 客户端：连接管理端，启动轮询
        try (RuleEngineClient client = RuleEngineClient.builder()
                .serverUrl(sdkBaseUrl())
                .tenantId(TENANT_ID)
                .pollInterval(Duration.ofSeconds(2))
                .build()) {

            // 等待初次拉取完成 → 评估大额交易 → 命中
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                RuleEvent bigTrade = new RuleEvent(TENANT_ID, "merchant-trade", "trade",
                        "merchant-1", UUID.randomUUID().toString(),
                        Instant.now(), Map.of(), Map.of("amount", 8000),
                        EventSource.SDK);
                assertThat(client.evaluate(bigTrade).ruleHit()).isTrue();
            });

            // 小额交易 → 不命中
            RuleEvent smallTrade = new RuleEvent(TENANT_ID, "merchant-trade", "trade",
                    "merchant-1", UUID.randomUUID().toString(),
                    Instant.now(), Map.of(), Map.of("amount", 100),
                    EventSource.SDK);
            assertThat(client.evaluate(smallTrade).ruleHit()).isFalse();
        }
    }

    @Test
    void sdkMetricDefinitions_accessibleAfterAdminCreatesMetric() {
        createScene("sdk-metric-scene", "SDK指标场景", "PULL", "USER", List.of("event"));
        createMetric("sdk-counter", "SDK计数器", "ATTRIBUTE", "LONG",
                Map.of(), 60, true);

        var resp = restTemplate.getForEntity(
                sdkBaseUrl() + "/metric-definitions?tenantId=" + TENANT_ID, List.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody()).isNotNull();
    }
}
```

- [ ] **Step 2: 运行测试**

```bash
$MVN verify -Pexamples -pl rule-example -Dtest=SdkTradingScenario
```

- [ ] **Step 3: 提交**

```bash
git add rule-example/src/test/java/com/sstlfsj/rule/example/scenario/SdkTradingScenario.java
git commit -m "feat(rule-example): add SdkTradingScenario — SDK 轮询 + 嵌入式交易风控"
```

---

### Task 7: 全量验证

- [ ] **Step 1: 运行所有 Scenario**

```bash
$MVN verify -Pexamples -pl rule-example
```

- [ ] **Step 2: 确认普通构建不受影响**

```bash
$MVN test
```

- [ ] **Step 3: 更新功能测试覆盖清单**

在 `docs/99-functional-test-coverage.md` 中把 4 个业务场景覆盖的 🟡 项更新为 ✅。

- [ ] **Step 4: 最终提交**

```bash
git add docs/99-functional-test-coverage.md
git commit -m "docs: 更新功能测试覆盖清单 — rule-example 业务场景覆盖 🟡 基建项"
```
