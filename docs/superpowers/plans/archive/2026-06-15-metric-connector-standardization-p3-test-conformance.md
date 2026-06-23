# 连接器标准化 P3 — 自助测试端点 + 一致性套件实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让接入方"自己点测试/跑套件、绿了就接"——跨源自助测试端点（实打实发一次，返回分阶段 trace）+ 带编号连接器契约 + 可独立运行的 conformance kit。

**Architecture:** `MetricFetchTester`（eval-svc）跨任意 sourceType 复用 P2 的 handler + 共用脊，捕获 `FetchTrace`；经 `MetricFetchTestService`（eval api）暴露给 rule-api 的 `:test` 端点。`rule-connector-conformance` 是独立 test-support Maven 模块（嵌入式 mock 上游 + 黄金用例），只依赖 `rule-kernel` + WireMock，不依赖 eval-svc。

**Tech Stack:** Spring MVC（PathPattern `:test`）/ WireMock / JUnit5 + AssertJ。

**前置：** P2 已合（handler、共用脊、`FetchTrace`、resolver）。

**范围红线：** 不做前端（P4）；OAuth2 token 交换若 P2 未完成，本计划 conformance 用例覆盖 STATIC_HEADER/BEARER，OAuth2 标 pending。

**环境：** `mvn-env` 设环境用 `$MVN`，跨模块 `-am`，结束 `$MVN clean test`。测试方法名英文。

---

## 文件结构

**Create（eval-svc）：**
- `rule-eval-svc/.../internal/metric/fetch/MetricFetchTester.java` — 跨源测试器，产 `FetchTrace`
- `rule-eval-svc/.../api/service/MetricFetchTestService.java` + `internal/service/MetricFetchTestServiceImpl.java`
- `rule-eval-svc/.../api/dto/FetchTraceView.java`（跨模块出参；或把 P2 的 `FetchTrace` 上移 api）

**Modify（rule-api）：**
- `rule-api/.../web/admin/ConnectorController.java`（加 `POST /{connectorCode}:test`）
- `rule-api/.../web/admin/MetricController.java`（加 `POST /{metricCode}:test`）

**Create（新模块）`rule-connector-conformance/`：**
- `pom.xml`（注册进根 `pom.xml` `<modules>`）
- `src/main/java/.../EmbeddedUpstream.java` `GoldenCase.java` `ConformanceSuite.java`
- `src/test/java/.../ConformanceSuiteTest.java`（自验套件）

**Modify（docs）：**
- `docs/04-extension.md`（§四加带编号连接器契约）

---

## Task 1: MetricFetchTester（跨源，产 FetchTrace）

**Files:**
- Create: `rule-eval-svc/.../internal/metric/fetch/MetricFetchTester.java`
- Test: `rule-eval-svc/.../internal/metric/fetch/MetricFetchTesterTest.java`

`MetricFetchTester` 注入 metric 定义解析（`MetricDefinitionResolver`）+ sourceType→handler map（与 `EvalContextAssembler` 同款收集，照其 `@MetricSourceType` 归类）+ `ConnectorDefinitionResolver`。给定 metricCode + 样例输入，构造 `MetricQuery`，调对应 handler 取数，捕获分阶段 `FetchTrace`。

> handler 需能"边取数边记 trace"。最简实现：`MetricFetchTester` 不改 handler 内部，而是**复刻取数流程的可观测版**——HTTP：用同样的 `VariableRenderer` 渲染出 request 文本、发请求、记原始响应、跑 `successWhen`、取值；SQL：用 `bind` 出 boundSql、执行、记首行。即 tester 直接调共用脊组件 + endpoint/connector resolver 自己编排，与 handler 复用同一批脊组件（不复制业务，复用 VariableRenderer/ErrorMapper/coerce）。若 handler 已把取数主体抽成可被 tester 调用的方法更佳——执行时评估二者，**优先抽公共方法复用、不复制逻辑**（DRY）。

- [ ] **Step 1: 写失败测试（HTTP + SQL 两源各一）**

```java
// MetricFetchTesterTest：
// HTTP：WireMock 桩 + mock 解析返回 connector descriptor → tester.test(metricCode, sampleVars, samplePayload, sampleSubjectId)
//   断言 FetchTrace.sourceType=EXTERNAL_HTTP, renderedRequest 含渲染后 URL, rawResponse 含桩响应, mappedValue 正确, errorCode null
// SQL：mock 数据源返回首行 → 断言 boundSql 含绑定后 SQL, coercedValue 正确
// 失败路径：上游 500 → FetchTrace.errorCode == "UPSTREAM_ERROR"
```

- [ ] **Step 2: 跑确认失败 → 写实现**

实现 `MetricFetchTester.test(String metricCode, Map<String,Object> sampleVars, Map<String,Object> samplePayload, String sampleSubjectId)` → `FetchTrace`。复用 P2 共用脊组件，按 sourceType 分支填 `FetchTrace`。`@Component`。

> sample* 是开放异构样本 → `Map<String,Object>`（CLAUDE.md 合规例外）。

- [ ] **Step 3: 跑通 → Commit**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/metric/fetch/MetricFetchTester.java \
        rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/metric/fetch/MetricFetchTesterTest.java
git commit -m "feat(eval): MetricFetchTester 跨源自助测试（产 FetchTrace）"
```

---

## Task 2: MetricFetchTestService（eval api）+ FetchTrace 出参

**Files:**
- Create: `rule-eval-svc/.../api/service/MetricFetchTestService.java`
- Create: `rule-eval-svc/.../internal/service/MetricFetchTestServiceImpl.java`
- 决策：P2 的 `FetchTrace` 在 internal；跨模块出 rule-api 需公开 → 把 `FetchTrace` 上移到 `rule-eval-svc/.../api/`（或新建 `api/dto/FetchTraceView`）。**优先上移 `FetchTrace` 到 eval api 包**（单一类型，避免重复映射），同步改 P2 import。

- [ ] **Step 1: 上移 FetchTrace 到 eval api 包**

`git mv` `FetchTrace.java` `internal/metric/fetch/` → `api/`（包名改 `...eval.api`），改 P2 引用点 import。跑 `$MVN -pl rule-eval-svc test-compile` 确认。

- [ ] **Step 2: 写 service 接口 + impl**

```java
package com.sstlfsj.rule.eval.api.service;

import com.sstlfsj.rule.eval.api.FetchTrace;
import java.util.Map;

/** metric 取数自助测试服务（跨源，供 admin :test 端点）。 */
public interface MetricFetchTestService {

    /**
     * 用样例输入实打实取数一次，返回分阶段 trace。
     *
     * @param tenantId        租户 id
     * @param metricCode      被测 metric
     * @param sampleVars      样例 vars（异构）
     * @param samplePayload   样例 payload（异构）
     * @param sampleSubjectId 样例主体 id
     * @return 分阶段 trace
     */
    FetchTrace test(Long tenantId, String metricCode,
                    Map<String, Object> sampleVars, Map<String, Object> samplePayload, String sampleSubjectId);

    /**
     * 直测某连接器（不经 metric，传临时 vars），返回分阶段 trace。供 connector :test 端点（用户选 A）。
     *
     * @param tenantId        租户 id
     * @param connectorCode   被测连接器
     * @param sampleVars      样例 vars（异构）
     * @param samplePayload   样例 payload（异构）
     * @param sampleSubjectId 样例主体 id
     * @return 分阶段 trace
     */
    FetchTrace testConnector(Long tenantId, String connectorCode,
                             Map<String, Object> sampleVars, Map<String, Object> samplePayload, String sampleSubjectId);
}
```
impl `@Service`，委托 `MetricFetchTester`（`test` → `MetricFetchTester.test`，`testConnector` → `MetricFetchTester.testConnector`）。`MetricFetchTester`（Task 1）需提供这两个方法。

- [ ] **Step 3: 编译 → Commit**

```bash
git add -A
git commit -m "feat(eval): MetricFetchTestService api（FetchTrace 上移 api 包）"
```

---

## Task 3: rule-api `:test` 端点（connector + metric）

**Files:**
- Modify: `rule-api/.../web/admin/ConnectorController.java`
- Modify: `rule-api/.../web/admin/MetricController.java`
- Test: `rule-api/.../web/admin/ConnectorTestEndpointTest.java`

D-1（设计蓝图）：`:test` 用 RFC 冒号风格 `@PostMapping("/{metricCode}:test")`，Spring 6 `PathPattern` 原生支持字面后缀。**集成测试是验收门**——若路由不匹配，回退子路径 `/{metricCode}/test`（与 `/publish` 同构）。

- [ ] **Step 1: 写失败测试（重点验冒号路由真匹配）**

```java
// ConnectorTestEndpointTest（MockMvc）：
// mock MetricFetchTestService 返回 FetchTrace(...)
// POST /admin/v1/metrics/m1:test  body {sampleVars,samplePayload,sampleSubjectId}
//   → status 200，jsonPath $.data.sourceType / $.data.mappedValue
// POST /admin/v1/connectors/c1:test 同理（connector:test 走"用该 connector 临测"或复用 metric tester——见下）
// 显式断言：路径含冒号能命中 controller 方法（不 404）
```

- [ ] **Step 2: 跑确认失败 → 加端点**

`MetricController` 加：
```java
@PostMapping("/{metricCode}:test")
public ApiResponse<FetchTrace> test(@PathVariable String metricCode,
                                    @RequestParam String tenantId,
                                    @RequestBody TestRequest req) {
    return ApiResponse.ok(testService.test(Long.valueOf(tenantId), metricCode,
            req.sampleVars(), req.samplePayload(), req.sampleSubjectId()));
}

/** 自助测试样例入参（异构样本，Map 合规例外）。 */
public record TestRequest(Map<String, Object> sampleVars, Map<String, Object> samplePayload, String sampleSubjectId) {}
```
**决策：本任务两个 `:test` 都做（用户选 A）。**
- metric `:test`（`MetricController`）：调 `MetricFetchTestService.test(tenantId, metricCode, sample...)`。
- connector `:test`（`ConnectorController`）：connector 自身不绑 metricCode，测试传临时 vars 直测连接器。`MetricFetchTester` 加重载 `testConnector(Long tenantId, String connectorCode, Map sampleVars, Map samplePayload, String sampleSubjectId)`——解析该 connector 描述符、构造临时 `MetricQuery(params={connector:code, vars:sampleVars}, ...)`、走 `DeclarativeHttpConnectorHandler` 同一取数路径、捕获 `FetchTrace`。`MetricFetchTestService` 加对应 `testConnector(...)` 方法。`ConnectorController` 加 `POST /{connectorCode}:test`。两个端点共用 `FetchTrace` 返回结构。

- [ ] **Step 3: 跑通（确认冒号路由匹配）→ Commit**

Run: `$MVN -pl rule-api -am test -Dtest=ConnectorTestEndpointTest`
若冒号路由 404 → 改 `/{metricCode}/test` 子路径并更新测试，提交说明注明回退原因。
```bash
git add -A
git commit -m "feat(api): metric/connector 自助测试 :test 端点（分阶段 trace）"
```

---

## Task 4: rule-connector-conformance 模块

**Files:**
- Create: `rule-connector-conformance/pom.xml`
- Modify: 根 `pom.xml`（`<modules>` 加 `rule-connector-conformance`）
- Create: `rule-connector-conformance/src/main/java/com/sstlfsj/rule/conformance/EmbeddedUpstream.java` `GoldenCase.java` `ConformanceSuite.java`
- Test: `rule-connector-conformance/src/test/java/com/sstlfsj/rule/conformance/ConformanceSuiteTest.java`

依赖只 `rule-kernel`（用 `MetricFetchError` 名做期望）+ WireMock（坐标在根 dependencyManagement，不写 version）+ junit/assertj。**不依赖 eval-svc**（对照的是 HTTP 行为契约，通过实测 HTTP 而非直调 handler）。

- [ ] **Step 1: 建模块 pom + 注册根 modules**

`rule-connector-conformance/pom.xml`：parent 指根 pom，artifactId `rule-connector-conformance`，依赖 `rule-kernel` + `org.wiremock:wiremock-standalone`（test/compile scope 视用法）+ junit-jupiter + assertj。根 `pom.xml` `<modules>` 加一行。
Run: `$MVN -q -pl rule-connector-conformance -am validate` 确认模块被识别。

- [ ] **Step 2: 写 EmbeddedUpstream（WireMock 包装）+ GoldenCase**

`EmbeddedUpstream.java`：启停 WireMock、按用例 stub 请求→响应、暴露 baseUrl + 请求记录校验。
`GoldenCase.java`：
```java
package com.sstlfsj.rule.conformance;

import java.util.Map;

/**
 * 连接器一致性黄金用例。
 *
 * @param name             用例名
 * @param stubPath         上游桩路径
 * @param stubStatus       上游桩响应状态
 * @param stubBody         上游桩响应体
 * @param expectedValue    期望映射值（成功用例）
 * @param expectedErrorCode 期望 MetricFetchError 名（失败用例），成功为 null
 */
public record GoldenCase(String name, String stubPath, int stubStatus, String stubBody,
                         Object expectedValue, String expectedErrorCode) {}
```

- [ ] **Step 3: 写 ConformanceSuite（跑用例集，对照契约）**

`ConformanceSuite.run(List<GoldenCase>)`：对每个用例起桩、按标准连接器语义发请求 + 映射、断言 value/errorCode 符合契约（§9.1 编号 Requirement）。可被接入方在自己仓库引依赖后独立 run。

- [ ] **Step 4: 写自验测试（覆盖成功 + 各失败码）**

```java
// ConformanceSuiteTest：跑一组黄金用例
//   C-成功：code=0 → value 命中
//   C-UPSTREAM_ERROR：status 500
//   C-PARSE_ERROR：valuePath 未命中
//   C-UNAUTHORIZED：status 401
// 全绿 = 套件自身正确
```

- [ ] **Step 5: 跑通 → Commit**

Run: `$MVN -pl rule-connector-conformance -am test`
```bash
git add rule-connector-conformance/ pom.xml
git commit -m "feat(conformance): rule-connector-conformance 一致性套件（mock 上游 + 黄金用例）"
```

---

## Task 5: 带编号连接器契约（docs）

**Files:**
- Modify: `docs/04-extension.md`（§四 加 MetricSource 章节后或新子节）

改 docs 前先跑 `doc-consistency-review` skill（CLAUDE.md 文档纪律：跨文档改动前扫自洽）。

- [ ] **Step 1: 写带编号契约**

在 `docs/04-extension.md` 加「连接器契约（Requirement 编号）」子节，仿 OpenFeature spec：
- C1.x 请求渲染：占位符命名空间集合、URL 编码规则
- C2.x 响应映射：successWhen 判定、valuePath 取值、未命中归 PARSE_ERROR
- C3.x 错误归一：状态/异常→MetricFetchError 映射表、errorMapping 覆盖顺序、默认 UPSTREAM_ERROR
- C4.x 鉴权：STATIC_HEADER/BEARER/OAUTH2_CC 各自语义、密钥引用不内联
- C5.x 弹性：retry 仅幂等、超时预算与热路径关系
每条编号对应 conformance kit 一个用例（交叉引用）。

- [ ] **Step 2: 跑文档自洽 + Commit**

`doc-consistency-review` 通过后：
```bash
git add docs/04-extension.md
git commit -m "docs(extension): 连接器带编号契约（对照 conformance kit）"
```

---

## Task 6: 全量兜底

- [ ] **Step 1: 全量 clean test**

Run: `$MVN clean test`
Expected: BUILD SUCCESS（新模块 rule-connector-conformance 纳入聚合构建全绿）

- [ ] **Step 2: 功能验证 `:test` 端点**

起服务，对已建 metric `POST /admin/v1/metrics/{code}:test` 传样例，核对返回 `FetchTrace` 分阶段字段真实（渲染 request / 原始响应 / 映射值 / errorCode），失败用例 errorCode 为细码。清理。

---

## Self-Review 记录

- **Spec 覆盖**：§9.1 带编号契约（Task5）、§9.2 conformance kit（Task4）、§9.3 自助测试端点 + 分阶段 trace（Task1-3）。
- **类型一致**：`FetchTrace`（P2 定义，Task2 上移 eval api）、`MetricFetchTester.test(...)`、`MetricFetchTestService.test(...)`、`TestRequest(sampleVars,samplePayload,sampleSubjectId)`、`GoldenCase(...)` 一致。
- **决策记录**：connector 独立 `:test` 与 metric `:test` 同期做（用户选 A，Task1 tester + Task2 service + Task3 端点各加 connector 重载）；`:test` 冒号路由有子路径回退（Task3 Step3）；conformance 不依赖 eval-svc（Task4）。
- **占位符**：MetricFetchTester 复用脊组件而非复制逻辑（Task1 提示 DRY，优先抽 handler 公共方法）。
