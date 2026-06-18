# rule-samples 接入示例库 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增 Maven 模块 `rule-samples`,用四个可运行 demo(裸 `main()` × 3 + Spring Boot app × 1)展示规则引擎四种接入姿势,作为接入方开发者的"使用指南即代码"。

**Architecture:** 一个新模块,按接入方式分子包(httpclient / sdkpolling / sdklocal / annotation),共享一个 admin 建配 helper(support/DemoConfig)。模块进 reactor 参与编译(公开 API 漂移即编译失败),但不带断言测试、不部署。

**Tech Stack:** Java 25 / Spring Boot 4 / rule-sdk / rule-sdk-spring-boot-starter / rule-kernel(api)/ Spring `RestClient`。

**测试纪律说明(本计划对 TDD 的有意偏离):** demo 是"给人抄 + 编译期校验"的示例代码,不写断言测试——其运行时正确性已由 `rule-app/src/test/.../example/scenario/` 的端到端测试覆盖。因此每个任务的验证 = **编译通过**;两个零依赖 demo(sdklocal / annotation)额外**实跑观察打印**;两个依赖服务端的 demo(httpclient / sdkpolling)给出精确运行命令 + 前提,实跑需先起 rule-app。

**前置环境:** 跑任何 `mvn` 命令前先用 `mvn-env` skill 设置 `$MVN`(本机 mvn 不在 PATH)。

**关键事实(实现时不要再猜,已查实):**
- `RuleEvent` 构造序:`(String tenantId, String sceneCode, String eventType, String subjectId, String eventId, Instant occurredAt, Map<String,Object> payload, Map<String,Object> providedMetrics, EventSource source)`。
- `ValueRef.PAYLOAD` 由 `EvalContextAssembler` 读 `event.payload()` → 示例字段 `amount` 放 **payload**(第 7 位)。
- `EvalResult` 是 record:`ruleHit()`(boolean)、`finalDecision()`(`Decision`,可 null,有 `.code()`)、`hitDecisions()`。
- admin 写接口(均在 `/admin/v1`,需 `X-Actor-Id` 头):`POST /scenes`、`POST /decisions?tenantId=`、`POST /rules`、`POST /rules/{ruleId}/publish?tenantId=`。`POST /rules` 返回 `data.ruleDefinitionId`(Number)。
- 公开评估:`POST /api/v1/rule/evaluate`,请求体 `{tenantCode, sceneCode, eventType, subjectId, eventId, occurredAt, payload}`,渠道由入口设为 HTTP,`tenantCode` 在边界解析为内部 id。
- **无租户创建 API、迁移不 seed 租户**:HTTP/轮询 demo 前提是租户 `(id=9001, code='samples')` 已存在(README 写成一行 SQL 前提)。
- SDK 客户端:`RuleEngineClient.builder().serverUrl(host).tenantId(id).pollInterval(Duration).build()`(轮询)/ `.ruleFile("classpath相对路径")`(本地 JSON)/ starter 自动从 `InlineRuleSpec` + `@ConditionType` Bean 装配。
- 本地 JSON 格式:`List<RuleVersionSnapshot>`,字段见 Task 5。
- rule-app 默认端口 8080。

---

## File Structure

| 文件 | 职责 |
|---|---|
| `rule-samples/pom.xml` | 模块定义 + 依赖 + exec-maven-plugin(跑 main) |
| `pom.xml`(根,修改) | `<modules>` 注册 `rule-samples` |
| `.../samples/support/DemoConfig.java` | admin 建配 helper(scene/decision/rule/publish),httpclient + sdkpolling 复用 |
| `.../samples/httpclient/HttpClientDemo.java` | HTTP 远程:建配 + `/api/v1/rule/evaluate` |
| `.../samples/sdkpolling/SdkPollingDemo.java` | SDK 轮询 + 本地评估 |
| `.../samples/sdklocal/SdkLocalDemo.java` | SDK 本地 JSON 规则源,零服务 |
| `rule-samples/src/main/resources/rules/large-trade.json` | sdklocal demo 的本地规则快照 |
| `.../samples/annotation/LargeTradeRule.java` | `@RuleDef` + `InlineRuleSpec` + Condition DSL |
| `.../samples/annotation/BusinessHoursEvaluator.java` | `@ConditionType` 自定义算子 |
| `.../samples/annotation/AnnotationDemoApplication.java` | Spring Boot 入口 + CommandLineRunner 评估打印 |
| `rule-samples/src/main/resources/application.yml` | annotation demo 配置(web-application-type=none) |
| `rule-samples/README.md` | 四姿势怎么选 + 各 demo 前提/跑法 |

Java 包根:`com.sstlfsj.rule.samples`。

---

### Task 1: 模块脚手架 + 注册 reactor

**Files:**
- Create: `rule-samples/pom.xml`
- Modify: `pom.xml`(根,`<modules>` 段,21-34 行附近)

- [ ] **Step 1: 写 `rule-samples/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.sstlfsj.rule</groupId>
        <artifactId>rule-engine</artifactId>
        <version>${revision}</version>
    </parent>

    <artifactId>rule-samples</artifactId>
    <packaging>jar</packaging>

    <dependencies>
        <dependency>
            <groupId>com.sstlfsj.rule</groupId>
            <artifactId>rule-kernel</artifactId>
        </dependency>
        <dependency>
            <groupId>com.sstlfsj.rule</groupId>
            <artifactId>rule-sdk</artifactId>
        </dependency>
        <dependency>
            <groupId>com.sstlfsj.rule</groupId>
            <artifactId>rule-sdk-spring-boot-starter</artifactId>
        </dependency>
        <!-- annotation demo 的 Spring 上下文（非 web） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
        </dependency>
        <!-- httpclient / sdkpolling 用的 RestClient -->
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-web</artifactId>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <!-- 用 mvn exec:java -Dexec.mainClass=... 跑各 demo -->
            <plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>exec-maven-plugin</artifactId>
                <version>3.5.0</version>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: 根 pom 注册模块**

在根 `pom.xml` 的 `<modules>` 内、`<module>rule-benchmark</module>` 之后加一行:

```xml
        <module>rule-samples</module>
```

- [ ] **Step 3: 建包目录占位并验证编译**

创建空目录(放后续类):`rule-samples/src/main/java/com/sstlfsj/rule/samples/`。
Run: `$MVN -pl rule-samples -am compile`
Expected: BUILD SUCCESS(模块被识别、无源码也能编过)。

- [ ] **Step 4: Commit**

```bash
git add rule-samples/pom.xml pom.xml
git commit -m "feat(rule-samples): 模块脚手架 + 注册 reactor"
```

---

### Task 2: support/DemoConfig — admin 建配 helper

**Files:**
- Create: `rule-samples/src/main/java/com/sstlfsj/rule/samples/support/DemoConfig.java`

- [ ] **Step 1: 写 DemoConfig**

```java
package com.sstlfsj.rule.samples.support;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * 通过 admin HTTP API 把示例场景 merchant-trade 的 scene/decision/rule 建好并发布,
 * 供 httpclient / sdkpolling demo 在评估前 seed 配置。
 * 前提:rule-app 已启动,且租户 (id=9001, code='samples') 已存在。
 */
public final class DemoConfig {

    /** 示例租户内部 id(admin 写接口用)。需与 README 的 seed SQL 一致。 */
    public static final String TENANT_ID = "9001";
    /** 示例租户 code(公开评估接口用)。 */
    public static final String TENANT_CODE = "samples";
    /** 示例场景编码。 */
    public static final String SCENE_CODE = "merchant-trade";

    private static final RestClient ADMIN = RestClient.create();

    private DemoConfig() {
    }

    /**
     * 建配并发布示例规则:scene → decision → rule → publish。
     * 注意:幂等性未处理,重复 seed 会因资源已存在而报错——demo 假设干净库或单次运行。
     *
     * @param baseUrl rule-app 根地址,如 http://localhost:8080
     */
    public static void seed(String baseUrl) {
        String admin = baseUrl + "/admin/v1";

        post(admin + "/scenes", Map.of(
                "tenantId", TENANT_ID,
                "sceneCode", SCENE_CODE,
                "name", "商户交易风控",
                "dominantMode", "PULL",
                "subjectType", "USER",
                "eventTypes", List.of("trade"),
                "payloadSchema", List.of(Map.of("name", "amount", "type", "NUMBER", "required", true)),
                "defaultParams", Map.of()));

        post(admin + "/decisions?tenantId=" + TENANT_ID, Map.of(
                "code", "REVIEW",
                "name", "人工审核",
                "priority", 50,
                "description", "samples",
                "actions", List.of()));

        Map<?, ?> ruleResp = post(admin + "/rules", Map.of(
                "tenantId", TENANT_ID,
                "sceneCode", SCENE_CODE,
                "code", "large-trade",
                "name", "大额交易",
                "conditionAst", Map.of(
                        "type", "ConditionNode",
                        "conditionType", "GT",
                        "metricCode", "amount",
                        "params", Map.of("threshold", 5000),
                        "valueRef", "PAYLOAD"),
                "decisionBindings", List.of(Map.of("decisionCode", "REVIEW")),
                "preGates", List.of(),
                "triggerEventTypes", List.of("trade"),
                "kind", "AST_BOOLEAN"));

        Map<?, ?> data = (Map<?, ?>) ruleResp.get("data");
        long ruleId = ((Number) data.get("ruleDefinitionId")).longValue();

        post(admin + "/rules/" + ruleId + "/publish?tenantId=" + TENANT_ID, null);
    }

    private static Map<?, ?> post(String url, Object body) {
        RestClient.RequestBodySpec spec = ADMIN.post()
                .uri(url)
                .header("X-Actor-Id", "samples")
                .contentType(MediaType.APPLICATION_JSON);
        return (body != null ? spec.body(body) : spec)
                .retrieve()
                .toEntity(Map.class)
                .getBody();
    }
}
```

- [ ] **Step 2: 验证编译**

Run: `$MVN -pl rule-samples -am compile`
Expected: BUILD SUCCESS。

- [ ] **Step 3: Commit**

```bash
git add rule-samples/src/main/java/com/sstlfsj/rule/samples/support/DemoConfig.java
git commit -m "feat(rule-samples): admin 建配 helper DemoConfig"
```

---

### Task 3: HttpClientDemo — HTTP 远程姿势

**Files:**
- Create: `rule-samples/src/main/java/com/sstlfsj/rule/samples/httpclient/HttpClientDemo.java`

- [ ] **Step 1: 写 HttpClientDemo**

```java
package com.sstlfsj.rule.samples.httpclient;

import com.sstlfsj.rule.samples.support.DemoConfig;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 接入姿势一:HTTP 远程。引擎是独立服务,接入方只发 REST。
 * <p>适合谁:不想嵌入 SDK、跨语言、希望规则集中在服务端的接入方。
 * <p>运行前提:rule-app 已在 localhost:8080 启动,且租户 (9001,'samples') 已存在(见 README)。
 * <p>怎么跑:{@code $MVN -pl rule-samples exec:java -Dexec.mainClass="com.sstlfsj.rule.samples.httpclient.HttpClientDemo"}
 */
public final class HttpClientDemo {

    private HttpClientDemo() {
    }

    public static void main(String[] args) {
        String baseUrl = "http://localhost:8080";

        // 1) 用 admin API 把场景/决策/规则建好并发布
        DemoConfig.seed(baseUrl);

        // 2) 公开评估接口:边界用 tenantCode,payload 携带 amount
        RestClient http = RestClient.create();
        Map<String, Object> evalBody = Map.of(
                "tenantCode", DemoConfig.TENANT_CODE,
                "sceneCode", DemoConfig.SCENE_CODE,
                "eventType", "trade",
                "subjectId", "merchant-1",
                "eventId", UUID.randomUUID().toString(),
                "occurredAt", Instant.now().toString(),
                "payload", Map.of("amount", 8000));

        Map<?, ?> resp = http.post()
                .uri(baseUrl + "/api/v1/rule/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .body(evalBody)
                .retrieve()
                .toEntity(Map.class)
                .getBody();

        System.out.println("[http-client] /api/v1/rule/evaluate response = " + resp);
    }
}
```

- [ ] **Step 2: 验证编译**

Run: `$MVN -pl rule-samples -am compile`
Expected: BUILD SUCCESS。

- [ ] **Step 3:(可选,需起服务)实跑**

前提:已起 rule-app 且 seed 了租户 SQL。
Run: `$MVN -pl rule-samples exec:java -Dexec.mainClass="com.sstlfsj.rule.samples.httpclient.HttpClientDemo"`
Expected: stdout 打印 response,`data.ruleHit=true`、`finalDecision.code=REVIEW`。若未起服务,跳过实跑,在交接时标注"未自动验证(需服务端)"。

- [ ] **Step 4: Commit**

```bash
git add rule-samples/src/main/java/com/sstlfsj/rule/samples/httpclient/HttpClientDemo.java
git commit -m "feat(rule-samples): HttpClientDemo — HTTP 远程接入"
```

---

### Task 4: SdkPollingDemo — SDK 轮询嵌入姿势

**Files:**
- Create: `rule-samples/src/main/java/com/sstlfsj/rule/samples/sdkpolling/SdkPollingDemo.java`

- [ ] **Step 1: 写 SdkPollingDemo**

```java
package com.sstlfsj.rule.samples.sdkpolling;

import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.sdk.RuleEngineClient;
import com.sstlfsj.rule.samples.support.DemoConfig;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 接入姿势二:SDK 轮询嵌入。SDK 定期从服务端拉规则快照,本地进程内零网络评估。
 * <p>适合谁:Java 接入方,要低延迟本地评估、又想规则在服务端集中管理。
 * <p>运行前提:rule-app 已在 localhost:8080 启动,且租户 (9001,'samples') 已存在(见 README);
 * demo 启动时先 seed 配置(boilerplate,非看点)。
 * <p>怎么跑:{@code $MVN -pl rule-samples exec:java -Dexec.mainClass="com.sstlfsj.rule.samples.sdkpolling.SdkPollingDemo"}
 * <p>starter 等价写法见 README(application.yml 配 rule.sdk.serverUrl/tenantId/pollInterval)。
 */
public final class SdkPollingDemo {

    private SdkPollingDemo() {
    }

    public static void main(String[] args) throws InterruptedException {
        String baseUrl = "http://localhost:8080";
        DemoConfig.seed(baseUrl);

        try (RuleEngineClient client = RuleEngineClient.builder()
                .serverUrl(baseUrl)
                .tenantId(DemoConfig.TENANT_ID)
                .pollInterval(Duration.ofSeconds(2))
                .build()) {

            // 等首次轮询拉取完成
            Thread.sleep(4000);

            RuleEvent big = event(8000);
            System.out.println("[sdk-polling] amount=8000 ruleHit=" + client.evaluate(big).ruleHit());

            RuleEvent small = event(100);
            System.out.println("[sdk-polling] amount=100  ruleHit=" + client.evaluate(small).ruleHit());
        }
    }

    private static RuleEvent event(int amount) {
        return new RuleEvent(
                DemoConfig.TENANT_ID, DemoConfig.SCENE_CODE, "trade", "merchant-1",
                UUID.randomUUID().toString(), Instant.now(),
                Map.of("amount", amount), Map.of(), EventSource.SDK);
    }
}
```

- [ ] **Step 2: 验证编译**

Run: `$MVN -pl rule-samples -am compile`
Expected: BUILD SUCCESS。

- [ ] **Step 3:(可选,需起服务)实跑**

Run: `$MVN -pl rule-samples exec:java -Dexec.mainClass="com.sstlfsj.rule.samples.sdkpolling.SdkPollingDemo"`
Expected: `amount=8000 ruleHit=true`、`amount=100 ruleHit=false`。未起服务则跳过并标注。

- [ ] **Step 4: Commit**

```bash
git add rule-samples/src/main/java/com/sstlfsj/rule/samples/sdkpolling/SdkPollingDemo.java
git commit -m "feat(rule-samples): SdkPollingDemo — SDK 轮询嵌入"
```

---

### Task 5: SdkLocalDemo — SDK 本地 JSON 规则源(零服务)

**Files:**
- Create: `rule-samples/src/main/resources/rules/large-trade.json`
- Create: `rule-samples/src/main/java/com/sstlfsj/rule/samples/sdklocal/SdkLocalDemo.java`

- [ ] **Step 1: 写本地规则快照 JSON**

`rule-samples/src/main/resources/rules/large-trade.json`:

```json
[
  {
    "ruleVersionId": 9001,
    "sceneCode": "merchant-trade",
    "tenantId": "9001",
    "kind": "AST_BOOLEAN",
    "triggerEventTypes": ["trade"],
    "decisionBindings": [{"decisionCode": "REVIEW", "priority": 50}],
    "preGates": [],
    "conditionAst": {
      "type": "ConditionNode",
      "conditionType": "GT",
      "metricCode": "amount",
      "params": {"threshold": 5000},
      "valueRef": "PAYLOAD"
    }
  }
]
```

- [ ] **Step 2: 写 SdkLocalDemo**

```java
package com.sstlfsj.rule.samples.sdklocal;

import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.sdk.RuleEngineClient;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 接入姿势三:SDK 本地 JSON 规则源。规则放本地文件,完全不连服务端,纯离线嵌入。
 * <p>适合谁:离线/边缘场景,或规则随应用一起发布、不需要服务端集中管理的接入方。
 * <p>运行前提:无,直接跑。
 * <p>怎么跑:{@code $MVN -pl rule-samples exec:java -Dexec.mainClass="com.sstlfsj.rule.samples.sdklocal.SdkLocalDemo"}
 */
public final class SdkLocalDemo {

    private SdkLocalDemo() {
    }

    public static void main(String[] args) {
        try (RuleEngineClient client = RuleEngineClient.builder()
                .ruleFile("rules/large-trade.json")
                .build()) {

            RuleEvent event = new RuleEvent(
                    "9001", "merchant-trade", "trade", "merchant-1",
                    UUID.randomUUID().toString(), Instant.now(),
                    Map.of("amount", 8000), Map.of(), EventSource.SDK);

            EvalResult result = client.evaluate(event);
            System.out.println("[sdk-local] amount=8000 ruleHit=" + result.ruleHit());
        }
    }
}
```

- [ ] **Step 3: 验证编译**

Run: `$MVN -pl rule-samples -am compile`
Expected: BUILD SUCCESS。

- [ ] **Step 4: 实跑(零依赖,必做)**

Run: `$MVN -pl rule-samples exec:java -Dexec.mainClass="com.sstlfsj.rule.samples.sdklocal.SdkLocalDemo"`
Expected: stdout 出现 `[sdk-local] amount=8000 ruleHit=true`。

- [ ] **Step 5: Commit**

```bash
git add rule-samples/src/main/resources/rules/large-trade.json rule-samples/src/main/java/com/sstlfsj/rule/samples/sdklocal/SdkLocalDemo.java
git commit -m "feat(rule-samples): SdkLocalDemo — SDK 本地 JSON 规则源"
```

---

### Task 6: annotation demo — 注解规则即代码 + 自定义算子(零服务)

**Files:**
- Create: `rule-samples/src/main/java/com/sstlfsj/rule/samples/annotation/LargeTradeRule.java`
- Create: `rule-samples/src/main/java/com/sstlfsj/rule/samples/annotation/BusinessHoursEvaluator.java`
- Create: `rule-samples/src/main/java/com/sstlfsj/rule/samples/annotation/AnnotationDemoApplication.java`
- Create: `rule-samples/src/main/resources/application.yml`

- [ ] **Step 1: 写 `@RuleDef` 规则即代码**

`LargeTradeRule.java`:

```java
package com.sstlfsj.rule.samples.annotation;

import com.sstlfsj.rule.kernel.api.annotation.DecisionBinding;
import com.sstlfsj.rule.kernel.api.annotation.RuleDef;
import com.sstlfsj.rule.sdk.Condition;
import com.sstlfsj.rule.sdk.InlineRuleSpec;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 注解规则即代码:大额交易且在营业时段 → REVIEW。
 * <p>{@code @RuleDef} 声明规则元数据(版本/租户/场景/触发/决策绑定),
 * {@code condition()} 用 Condition DSL 链式写条件;starter 自动扫描装载。
 */
@RuleDef(
        id = 9001L,
        tenantId = "9001",
        sceneCode = "merchant-trade",
        trigger = "trade",
        decisions = @DecisionBinding(code = "REVIEW", priority = 50))
@Component
public class LargeTradeRule implements InlineRuleSpec {

    @Override
    public Condition condition() {
        // 内置算子 payloadGt + 自定义算子 BUSINESS_HOURS 组合
        return Condition.payloadGt("amount", 5000)
                .and(Condition.of("BUSINESS_HOURS", "hour", Map.of()));
    }
}
```

- [ ] **Step 2: 写 `@ConditionType` 自定义算子**

`BusinessHoursEvaluator.java`:

```java
package com.sstlfsj.rule.samples.annotation;

import com.sstlfsj.rule.kernel.api.annotation.ConditionType;
import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import org.springframework.stereotype.Component;

/**
 * 自定义算子示例:内置算子不够用时,实现 ConditionEvaluator + 标 {@code @ConditionType},
 * starter 自动注册成新算子类型 BUSINESS_HOURS。
 * <p>本算子直接读事件 payload.hour 判断是否落在营业时段 [9,18)(demo 用确定值便于复现)。
 */
@ConditionType("BUSINESS_HOURS")
@Component
public class BusinessHoursEvaluator implements ConditionEvaluator {

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        Object raw = ctx.event().payload().get("hour");
        int hour = raw instanceof Number n ? n.intValue() : -1;
        return hour >= 9 && hour < 18;
    }
}
```

- [ ] **Step 3: 写 Spring Boot 入口**

`AnnotationDemoApplication.java`:

```java
package com.sstlfsj.rule.samples.annotation;

import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.sdk.RuleEngineClient;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 接入姿势四:注解规则即代码(Spring Boot starter)。规则用 Java 类 + 注解声明,
 * starter 自动扫描 {@code @RuleDef} / {@code @ConditionType} Bean 装配 RuleEngineClient,
 * 完全不连服务端。
 * <p>适合谁:规则逻辑随应用代码演进、希望强类型 + IDE 重构友好的接入方。
 * <p>运行前提:无,直接跑。
 * <p>怎么跑:{@code $MVN -pl rule-samples exec:java -Dexec.mainClass="com.sstlfsj.rule.samples.annotation.AnnotationDemoApplication"}
 */
@SpringBootApplication
public class AnnotationDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnnotationDemoApplication.class, args);
    }

    /** 容器就绪后评估一个示例事件并打印(starter 已自动注入装配好的 RuleEngineClient)。 */
    @Bean
    CommandLineRunner demoRunner(RuleEngineClient client) {
        return args -> {
            RuleEvent event = new RuleEvent(
                    "9001", "merchant-trade", "trade", "merchant-1",
                    UUID.randomUUID().toString(), Instant.now(),
                    Map.of("amount", 8000, "hour", 10), Map.of(), EventSource.SDK);

            EvalResult result = client.evaluate(event);
            System.out.println("[annotation] amount=8000 hour=10 ruleHit=" + result.ruleHit()
                    + " finalDecision=" + (result.finalDecision() == null
                            ? null : result.finalDecision().code()));
        };
    }
}
```

- [ ] **Step 4: 写 application.yml**

`rule-samples/src/main/resources/application.yml`:

```yaml
# annotation demo:纯本地注解规则源,不连服务端(不设 rule.sdk.server-url)
spring:
  main:
    web-application-type: none
    banner-mode: off
```

- [ ] **Step 5: 验证编译**

Run: `$MVN -pl rule-samples -am compile`
Expected: BUILD SUCCESS。

- [ ] **Step 6: 实跑(零依赖,必做)**

Run: `$MVN -pl rule-samples exec:java -Dexec.mainClass="com.sstlfsj.rule.samples.annotation.AnnotationDemoApplication"`
Expected: stdout 出现 `[annotation] amount=8000 hour=10 ruleHit=true finalDecision=REVIEW`,随后进程正常退出。
排障:若 `ruleHit=false`,确认 starter 已扫描到 `BusinessHoursEvaluator`(算子未注册会导致 BUSINESS_HOURS 条件判负)与 `LargeTradeRule` Bean。

- [ ] **Step 7: Commit**

```bash
git add rule-samples/src/main/java/com/sstlfsj/rule/samples/annotation/ rule-samples/src/main/resources/application.yml
git commit -m "feat(rule-samples): 注解规则即代码 + @ConditionType 自定义算子 demo"
```

---

### Task 7: README — 使用指南索引

**Files:**
- Create: `rule-samples/README.md`

- [ ] **Step 1: 写 README**

```markdown
# rule-samples — 接入姿势示例库

面向**接入方开发者**的"使用指南即代码":每种接入姿势一个能跑的最小 demo,可直接复制进自己工程。
与 [`docs/examples/`](../docs/examples/)(声明式配置 + curl)、`rule-app/src/test` 端到端测试(验正确性)互补——本模块是 **Java 接入代码**,裸 `main()` 跑、打印结果、不带断言、不在 CI 执行,仅参与编译以校验公开 API 不漂移。

## 四种接入姿势怎么选

| 姿势 | demo | 连服务端? | 适合谁 |
|---|---|---|---|
| HTTP 远程 | `httpclient/HttpClientDemo` | 是 | 跨语言 / 规则集中服务端 / 不嵌 SDK |
| SDK 轮询嵌入 | `sdkpolling/SdkPollingDemo` | 是 | Java 接入,要本地低延迟评估 + 服务端集中管理 |
| SDK 本地 JSON | `sdklocal/SdkLocalDemo` | 否 | 离线 / 边缘,规则随应用发布 |
| 注解规则即代码 | `annotation/AnnotationDemoApplication` | 否 | 规则随代码演进,要强类型 + 重构友好 |

## 运行前提

- 跑 mvn 前先设置 `$MVN`(本机 mvn 不在 PATH)。
- **HTTP / SDK 轮询**两个 demo 需要:
  1. 起 rule-app(用打包产物,别用 reactor run 目标):`java -jar rule-app/target/rule-app-*.jar`
  2. 租户 `(id=9001, code='samples')` 先存在(当前无租户创建 API),执行一次:
     ```sql
     INSERT INTO tenant (id, code, name) VALUES (9001, 'samples', '示例租户');
     ```
- **SDK 本地 / 注解**两个 demo:零依赖,直接跑。

## 跑各 demo

```bash
$MVN -pl rule-samples exec:java -Dexec.mainClass="com.sstlfsj.rule.samples.sdklocal.SdkLocalDemo"
$MVN -pl rule-samples exec:java -Dexec.mainClass="com.sstlfsj.rule.samples.annotation.AnnotationDemoApplication"
$MVN -pl rule-samples exec:java -Dexec.mainClass="com.sstlfsj.rule.samples.httpclient.HttpClientDemo"
$MVN -pl rule-samples exec:java -Dexec.mainClass="com.sstlfsj.rule.samples.sdkpolling.SdkPollingDemo"
```

## SDK 轮询的 starter 等价写法

`SdkPollingDemo` 用 builder 手动构造。用 starter 时,只需在 `application.yml` 配置,starter 自动装配 `RuleEngineClient` Bean:

```yaml
rule:
  sdk:
    server-url: http://localhost:8080
    tenant-id: "9001"
    poll-interval: 2s
    scenes:
      - merchant-trade
```

注入即用:`@Autowired RuleEngineClient client;`。
```

- [ ] **Step 2: Commit**

```bash
git add rule-samples/README.md
git commit -m "docs(rule-samples): 接入姿势使用指南 README"
```

---

### Task 8: 全量编译兜底

**Files:** 无新增。

- [ ] **Step 1: reactor 全量编译**

Run: `$MVN -pl rule-samples -am clean compile`
Expected: BUILD SUCCESS。确认新模块在干净构建下与 rule-sdk / rule-kernel / starter 正确联编。

- [ ] **Step 2:(若前序未跑)实跑两个零依赖 demo 确认运行时无异常**

Run:
```bash
$MVN -pl rule-samples exec:java -Dexec.mainClass="com.sstlfsj.rule.samples.sdklocal.SdkLocalDemo"
$MVN -pl rule-samples exec:java -Dexec.mainClass="com.sstlfsj.rule.samples.annotation.AnnotationDemoApplication"
```
Expected: 分别打印 `ruleHit=true`(annotation 含 `finalDecision=REVIEW`)。

---

## Self-Review(写计划后自检)

**Spec coverage:**
- §一 定位与边界 → README(Task 7)+ 计划测试纪律说明。✅
- §二 四种姿势 → Task 3/4/5/6 各一。✅
- §三 模块结构 → Task 1 pom + 各 Task 文件路径一致。✅
- §四 各 demo 行为规约 → Task 3/4/5/6 代码逐条对应(merchant-trade / amount>5000 / REVIEW / payload 取值)。✅
- §四 support/DemoConfig → Task 2。✅
- §五 进 reactor、编译期校验、不跑不部署 → Task 1 Step 2 + 各 Task 验证=编译。✅
- §六 YAGNI → 无矩阵、无 Testcontainers、无断言测试,符合。✅

**Placeholder scan:** 无 TBD/TODO;所有代码步骤含完整代码;命令含预期输出。✅

**Type consistency:**
- `RuleEvent` 九参顺序在 Task 4/5/6 一致,`amount` 均在第 7 位 payload。✅
- `DemoConfig.TENANT_ID="9001"` / `TENANT_CODE="samples"` / `SCENE_CODE="merchant-trade"` 跨 Task 3/4 引用一致。✅
- `large-trade.json` 的 tenantId/sceneCode/triggerEventTypes 与注解/builder demo 一致(9001 / merchant-trade / trade)。✅
- `Condition.payloadGt` / `Condition.of` / `Condition.and` 均为已查实存在的 DSL 方法。✅
- `EvalResult.ruleHit()` / `finalDecision().code()` 与 record 定义一致。✅
```
