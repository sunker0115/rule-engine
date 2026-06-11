# rule-example 模块设计

> 纯测试模块，以「可运行的示例场景」形式验证需要真实外部依赖的链路。

## 一、模块定位

`rule-example` 是 Maven reactor 中的叶子模块，依赖 `rule-app`，不被任何人依赖。每个 Scenario 使用 `@SpringBootTest` 启动完整服务上下文，通过 admin API 搭建配置，调用 eval/job 运行接口，查数据库确认落库正确。

**覆盖范围：**
- 4 个完整业务场景，每个自然覆盖一种基建依赖（SQL_AGGREGATE / EXTERNAL_HTTP / webhook 投递 / SDK 轮询）
- dry-run 两端入口（精确版本 + ruleId DRAFT）在首个场景中一并覆盖

**不覆盖：**
- 已有模块级单元测试覆盖的逻辑
- 已在功能测试中手工验证的路径（除非纳入冒烟）

## 一、测试规范（铁律）

**场景即业务故事。** 每个 Scenario 建模一个完整的端到端业务用例，描述一个业务上可理解的完整故事——从风控配置到告警通知。禁止为验证某个 handler / 某种 infrastructure 而独立建纯技术场景。

这条规范来自以下判断：

1. **业务故事跑通了，基建自然就验到了。** "高风险登录异常 → 告警通知安全团队"走到 hit 时，`SendAlertHandler` 的 webhook 投递、`action_execution` 落库就都验了——不需要一个独立的 `SendAlertWebhookScenario` 来测 handler。
2. **纯技术场景缺乏上下文。** 一个"建个 PUSH scene → 发个事件 → 看异步落库"的场景，看不懂这个功能在真实业务里解决什么问题。
3. **减少维护成本。** 每个技术点独立一个 Scenario，随着 handler/基础设施变化，多个场景要改。业务故事聚合后，底层变更影响范围更集中。

**违例判断**：如果一个 Scenario 的 test 方法名可以直接替换成 "验证 XXX handler 能跑通"，它就是纯技术测试，不该存在。正确的方法是 "异常登录 → 命中高风险规则 → 触发告警通知安全团队"。

## 二、架构

```
rule-example (test only)
  ├── depends on rule-app (→ 间接获得所有模块 + MyBatis + Flyway)
  ├── testcontainers:mysql     → 真实 MySQL 容器
  ├── testcontainers:junit-jupiter
  ├── spring-boot-testcontainers
  └── wiremock-standalone      → 模拟外部 HTTP 端点
```

**数据库：** 使用独立数据库 `rule-engine-example`，与主库 `rule_engine` 隔离。所有 Scenario 共享同一个 MySQL 容器，但表截断（非 DROP）保证场景间数据隔离。

**构建 profile：** 父 POM 中作为独立 profile 子模块，默认不激活。

```xml
<profile>
    <id>examples</id>
    <modules><module>rule-example</module></modules>
</profile>
```

- `mvn test` → 不跑
- `mvn verify -Pexamples` → 激活
- CI 可配独立 job

## 三、模块结构

```
rule-example/
├── pom.xml
├── src/test/java/com/sstlfsj/rule/example/
│   ├── ScenarioSupport.java                    # 基类：上下文 + 清理 + API/DB 工具方法
│   └── scenario/
│       ├── HighRiskLoginScenario.java          # 异常登录检测 → PUSH 异步告警
│       ├── OrderFraudScenario.java             # 订单风控 → SQL_AGGREGATE 取数
│       ├── CreditEvaluationScenario.java       # 信用评估 → EXTERNAL_HTTP 取数
│       └── SdkTradingScenario.java             # SDK 交易风控 → 轮询 + 嵌入式评估
└── src/test/resources/
    ├── application-test.yml                    # MySQL + WireMock 配置
    └── sql/
        └── V1__business_tables.sql             # 业务表 DDL（SQL_AGGREGATE 取数源）
```

**命名约定：**
- 场景类以 `Scenario` 结尾，不使用 `Test` 后缀
- JUnit Jupiter 作为运行器，Failsafe 执行（`*Scenario.java` 匹配 `maven-failsafe-plugin`）
- 它们首先是活的文档示例，其次才是验证——失败意味着"该场景在当前代码里跑不通"

## 四、Scenario 生命周期

每个 Scenario 继承 `ScenarioSupport`，遵循三步结构：

```
准备 → 执行 → 验证
```

```java
@SpringBootTest(classes = RuleEngineApplication.class)
@Testcontainers
abstract class ScenarioSupport {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("rule-engine-example");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", mysql::getJdbcUrl);
        r.add("spring.datasource.username", mysql::getUsername);
        r.add("spring.datasource.password", mysql::getPassword);
    }

    // 工具方法：调 admin API
    SceneEntity createScene(String json);      // POST /admin/v1/scenes
    MetricEntity createMetric(String json);    // POST /admin/v1/metrics
    RuleEntity createRule(String json);        // POST /admin/v1/rules
    void publishRule(Long ruleId);             // POST /admin/v1/rules/{id}/publish

    // 工具方法：调运行 API
    EvalResponse evaluate(String json);        // POST /api/v1/evaluate
    EvalResponse dryRun(Long versionId);       // POST /api/v1/dry-run?ruleVersionId=
    PushResponse pushEvent(String json);       // POST /api/v1/event
    void triggerJob(String code);              // POST /admin/v1/jobs/{code}/trigger

    // 工具方法：直接查库验证
    List<EvaluationSession> findSessions();    // MyBatis mapper
    List<NodeTrace> findTraces();
    List<ActionExecution> findActions();

    @AfterEach
    void cleanup() {
        // TRUNCATE 所有业务表，不 DROP（保留 DDL）
    }
}
```

**具体 Scenario 示例：**

```java
class HighRiskLoginScenario extends ScenarioSupport {

    @Test
    void evaluate_matchingRule_savesSessionAndTrace() {
        // 准备
        createScene(sceneJson("high-risk-login"));
        createMetric(metricJson("login-count"));
        createDecision(decisionJson("send-alert"));
        Long ruleId = createRule(ruleJson("high-risk-login"));
        publishRule(ruleId);

        // 执行
        EvalResponse resp = evaluate("""
            {"eventType": "login", "payload": {"userId": "u1", "ip": "1.2.3.4"}}
            """);

        // 验证
        assertThat(resp.ruleHit()).isTrue();
        assertThat(findSessions()).hasSize(1);
        assertThat(findTraces()).isNotEmpty();
        assertThat(findActions()).hasSize(1);
    }
}
```

## 五、业务场景详述

### 5.1 异常登录检测 — `HighRiskLoginScenario`

**业务故事：** 安全团队配置高风险登录规则——同一用户从新 IP 短时间多次登录 → 触发告警通知。

**覆盖：**
- PUSH scene + PULL evaluate + async push event
- decision 挂 SEND_ALERT action → WireMock 验证 webhook 投递 + action_execution 落库
- dry-run 两种入口（精确版本 ruleVersionId + ruleId 取最新 DRAFT）
- 断言：evaluation_session + node_trace + action_execution（SUCCESS/FAILED）全落库

**步骤：**
1. 建 scene（PUSH，eventType=login）+ metric（login_count, SQL_AGGREGATE）+ decision（SEND_ALERT，webhookUrl 指向 WireMock）
2. 建规则（login_count > 3 AND ip_fresh = true）→ 发布
3. PULL evaluate：模拟 u1 的 4 次登录 → 命中 + action 派发 → 断言 WireMock 收到 webhook + action_execution=SUCCESS
3b. dry-run：按精确版本 dry-run → dry_run_session 落库，不影响 evaluation_session
3c. dry-run：按 ruleId dry-run（规则已发布，仍有 ACTIVE 版本）→ 取到正确版本
4. PUSH event：推送 u2 的异常登录事件 → 异步 HIT → await 断言 evaluation_session + webhook

### 5.2 订单风控 — `OrderFraudScenario`

**业务故事：** 风控团队配置订单风控规则——当日订单总金额超 10000 即拦截。

**覆盖：**
- SQL_AGGREGATE 取数（业务表 orders，同一 MySQL 容器）
- PULL evaluate 命中/未命中两端

**步骤：**
1. 灌业务数据（orders 表：u1 两笔共 15000，u2 一笔 500）
2. 建 scene + metric（order_daily_sum, SQL_AGGREGATE, SQL: `SELECT SUM(amount) FROM orders WHERE user_id = :subjectId AND created_at >= CURDATE()`）+ decision（BLOCK）+ 规则（order_daily_sum > 10000 → BLOCK）
3. evaluate u1 → HIT（金额超阈值）
4. evaluate u2 → MISS（金额不足）
5. 断言 session/trace 落库

### 5.3 信用评估 — `CreditEvaluationScenario`

**业务故事：** 贷款业务调用外部信用评分接口——评分 < 600 拒贷。

**覆盖：**
- EXTERNAL_HTTP 取数（WireMock 模拟外部评分 API）
- handler 发起真实 HTTP GET → JSONPath 提取 → 类型转换 → 条件求值

**步骤：**
1. WireMock stub：`GET /api/credit/score/{uid}` → 对不同 uid 返回不同分数
2. 建 scene + metric（credit_score, EXTERNAL_HTTP, urlTemplate `/api/credit/score/{payload.uid}`, jsonPath `data.score`）+ decision（REJECT）+ 规则（credit_score < 600 → REJECT）
3. evaluate 高分用户（uid=u_rich，返回 850）→ MISS
4. evaluate 低分用户（uid=u_poor，返回 350）→ HIT → decision=REJECT

### 5.4 SDK 交易风控 — `SdkTradingScenario`

**业务故事：** 商户端 SDK 嵌入规则引擎——定期从规则管理端拉取最新规则 → 在本地对交易做实时风控评估。

**覆盖：**
- SDK HTTP 快照/指标定义端点
- PollingRuleSource 拉取 → 本地索引刷新 → RuleEngineClient 嵌入式评估

**步骤：**
1. 通过 admin API 建 scene + decision（REVIEW）+ rule（交易金额 > 5000 → 人工审核）→ 发布
2. 验证 SDK 快照端点返回了已发布规则（GET /sdk/v1/snapshots）
3. 构建 RuleEngineClient（serverUrl 指向 local rule-app），启动轮询
4. await 初次拉取完成 → 喂入交易事件（amount=8000）→ 命中
5. 喂入小额交易（amount=100）→ 未命中

## 六、配置

WireMock 在 `ScenarioSupport` 中以 `static WireMockServer` 方式启动（随机端口），端口号在 `@BeforeAll` 中注入 `@DynamicPropertySource`，不在 `application-test.yml` 中声明。

`application-test.yml`：

```yaml
spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
  flyway:
    enabled: true
    locations: classpath:db/migration,classpath:sql
  testcontainers:
    mysql:
      database-name: rule-engine-example

engine:
  rule:
    metric:
      source:
        business-db:                # SQL_AGGREGATE 数据源
          type: MYSQL
          url: ${spring.datasource.url}
          username: ${spring.datasource.username}
          password: ${spring.datasource.password}
```

## 七、pom.xml 骨架

```xml
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
```

## 八、执行方式

```bash
# 本地：设置环境后跑
$MVN verify -Pexamples -pl rule-example

# CI：独立 job
mvn verify -Pexamples
```
