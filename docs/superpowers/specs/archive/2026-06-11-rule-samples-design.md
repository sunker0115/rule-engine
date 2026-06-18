# rule-samples 模块设计 — 接入姿势示例库

> 状态:设计待评审 · 日期 2026-06-11

## 一、目标与定位

新增 Maven 模块 `rule-samples`,作为**面向接入方开发者的"使用指南即代码"**:每种接入姿势一个能 `main()`(或 Spring Boot 启动)跑起来的最小 demo + README,展示"作为使用者,我在自己工程里怎么写代码调规则引擎"。代码可直接复制进接入方工程。

### 与现有两套 example 资产的边界(互补,不重叠)

| 资产 | 形态 | 受众 | 看点 |
|---|---|---|---|
| `docs/examples/` | 声明式配置 JSON(scene/rules/metrics)+ curl 剧本 | 配置作者 / 评审 | 配置长什么样、dry-run 跑通 |
| `rule-app/src/test/.../example/scenario/` | 带断言的端到端测试(Testcontainers + WireMock + JUnit) | 维护者 / CI | 验证引擎行为正确 |
| **`rule-samples`(本设计)** | 裸 `main()` / Spring Boot 小 app,打印结果、**不断言** | **接入方开发者** | **怎么在代码里接引擎** |

与昨天(commit `e40dacb`)删除的 `rule-example` 模块的关系:那个模块承载的是端到端**测试**,已迁入 `rule-app/src/test`。`rule-samples` 不是它的复活——用途不同(给人抄 vs 验正确性)、不带断言、不跑 Testcontainers、不在 CI 执行,仅参与**编译**(见 §五)。命名上也用 `rule-samples` 与之区分。

## 二、四种接入姿势(每种一个 demo)

覆盖四种接入心智,正交不重复:

1. **HTTP 远程**(`httpclient`)——引擎是独立服务,接入方只发 REST。
2. **SDK 轮询嵌入**(`sdkpolling`)——SDK 从服务端拉快照,**本地进程内零网络评估**。
3. **SDK 本地 JSON 规则源**(`sdklocal`)——规则以 JSON 文件/快照本地装载,**完全不连服务端**。
4. **注解规则即代码**(`annotation`)——规则用 `@RuleDef` 写成 Java 类,Spring starter 自动扫描装配,**完全不连服务端**;同 demo 展示 `@ConditionType` 自定义算子扩展。

## 三、模块结构

```
rule-samples/
├── pom.xml
├── README.md
└── src/main/
    ├── java/com/sstlfsj/rule/samples/
    │   ├── httpclient/HttpClientDemo.java
    │   ├── sdkpolling/SdkPollingDemo.java
    │   ├── sdklocal/SdkLocalDemo.java
    │   ├── annotation/
    │   │   ├── AnnotationDemoApplication.java   Spring Boot 入口 + CommandLineRunner
    │   │   ├── LargeTradeRule.java               @RuleDef + InlineRuleSpec + Condition DSL
    │   │   └── BusinessHoursEvaluator.java        @ConditionType 自定义算子 Bean
    │   └── support/DemoConfig.java                admin 建配 helper(httpclient/sdkpolling 复用)
    └── resources/
        ├── rules/large-trade.json                sdklocal demo 用的本地 JSON 规则
        └── application.yml                        annotation demo 的 rule.sdk.* 配置(本地模式)
```

每个 `*Demo` 类头 Javadoc 必须写清三件事:**适合谁 / 运行前提 / 怎么跑**。

### pom 依赖

- `rule-sdk`(RuleEngineClient / Condition / RuleSource)
- `rule-sdk-spring-boot-starter`(annotation demo 的自动装配)
- `rule-kernel`(api:RuleEvent / EvalResult / @RuleDef / @ConditionType / EventSource)
- `spring-boot-starter`(annotation demo 的 Spring 上下文 + Spring Web 的 RestClient,httpclient/sdkpolling 复用)
- 不依赖任何 `rule-*-svc` / `rule-app`(接入方不该看到服务端内部实现)

## 四、各 demo 行为规约

> 共同约定:所有 demo 用同一示例场景 `merchant-trade`(商户交易风控,payload 字段 `amount`,规则"金额 > 5000 → REVIEW")。租户 code = `samples`、tenantId 由 demo seed 时解析。统一把 `EvalResult` 打印到 stdout,不做断言。

### 4.1 `HttpClientDemo`(纯 main,HTTP 远程)
- **前提**:rule-app 已在 `localhost:8080` 启动(空库即可)。
- **流程**:用 Spring `RestClient` 调 admin(带 `X-Actor-Id` 头)依序 `POST /admin/v1/scenes` → `/decisions` → `/rules` → `/rules/{id}/publish`,再 `POST /api/v1/rule/evaluate`(请求体 `tenantCode/sceneCode/eventType/subjectId/eventId/occurredAt/payload`)拿同步结果打印。
- **看点**:admin 建配脚本 + 公开评估的完整 HTTP 调用形态。配置步骤复用 `support/DemoConfig`。

### 4.2 `SdkPollingDemo`(纯 main,SDK 轮询)
- **前提**:rule-app 已启动 **且配置已存在**——demo 启动时先用 `support/DemoConfig` seed 配置(boilerplate,非看点)。
- **流程**:`RuleEngineClient.builder().serverUrl("http://localhost:8080").tenantId(..).pollInterval(Duration.ofSeconds(2)).build()`,等首次拉取完成后 `client.evaluate(RuleEvent)` 本地评估并打印;大额命中、小额不命中各跑一次。
- **看点**:SDK 客户端构造 + 轮询 + 零网络本地评估。README 附 starter `application.yml` 等价写法(`rule.sdk.serverUrl/tenantId/pollInterval`)。

### 4.3 `SdkLocalDemo`(纯 main,本地 JSON 规则源)
- **前提**:无,直接跑。
- **流程**:`RuleEngineClient.builder().ruleFile("classpath:rules/large-trade.json").build()`,`client.evaluate(..)` 打印。
- **看点**:不连服务端、规则放本地文件的离线嵌入用法。

### 4.4 `annotation` 包(Spring Boot starter app,注解规则即代码)
- **前提**:无,`AnnotationDemoApplication` 直接 `main()` 启动。
- **构成**:
  - `LargeTradeRule` —— `@RuleDef(id, tenantId, sceneCode="merchant-trade", trigger="trade", decisions=@DecisionBinding(code="REVIEW", priority=50))` + 实现 `InlineRuleSpec`,`condition()` 返回 `Condition.gt("amount", 5000)`。
  - `BusinessHoursEvaluator` —— `@ConditionType("BUSINESS_HOURS")` + 实现 `ConditionEvaluator`,演示内置算子不够用时如何扩自定义算子 Bean。
  - `AnnotationDemoApplication` —— starter 自动收集上述 Bean 装配 `RuleEngineClient`;`CommandLineRunner` 评估示例事件打印。
  - `application.yml` —— 本地模式配置(不设 `rule.sdk.serverUrl`,纯注解规则源)。
- **看点**:声明 Bean 即生效的注解装配;`@RuleDef` 规则即代码 + `@ConditionType` 自定义算子两件事一次讲透。

### `support/DemoConfig`
封装 admin 建配的 RestClient 调用(mirror `ScenarioSupport` 的 createScene/createDecision/createRule/publishRule,去掉 JdbcTemplate/Testcontainers),供 httpclient、sdkpolling 复用。仅此一处共享 helper,不做更多抽象。

## 五、构建与 CI

- 加入根 `pom.xml` 的 `<modules>`,使其参与编译——demo 充当"**编译期校验的活文档**",公开 API(RuleEngineClient builder / Condition DSL / @RuleDef / EvalEventRequest 等)一旦改坏,编译失败立即暴露。
- **不跑、不部署、不进任何服务运行时依赖**;模块无测试(裸 main/demo 不带断言)。

## 六、明确不做(YAGNI)

- 不做"接入方式 × 业务域"矩阵——四个姿势各一个最小场景即可。
- 不内嵌引擎 / 不引 Testcontainers——那是 `rule-app/src/test` 测试的职责。
- 不铺满业务域(风控/营销/活动)——那是 `docs/examples/` 的职责。
- 不为 demo 写自动化测试/断言——它的价值是"给人抄 + 编译期校验",运行行为由对应的 scenario 测试保证。

## 七、相关引擎能力引用

- SDK 客户端与 RuleSource:`rule-sdk`(`RuleEngineClient` / `Condition` / `PollingRuleSource` / `FileRuleSource` / `AnnotationRuleSource`)
- 注解:`rule-kernel` `api/annotation`(`@RuleDef` / `@DecisionBinding` / `@ConditionType`)
- starter 自动装配:`RuleEngineClientAutoConfiguration`(扫描 `InlineRuleSpec` / `@ConditionType` Bean)
- HTTP 入口:`EvalController`(`/api/v1/rule/evaluate`)、admin controllers(`/admin/v1/*`)
