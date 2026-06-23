# 连接器标准化 P2 — 评估运行时实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让评估期真正按连接器取数——`EXTERNAL_HTTP` 走声明式连接器（解析描述符→渲染→鉴权→弹性发请求→响应映射→错误归一），SQL 继承共用脊（statement 超时、错误细码、`:subject.x` 绑定），连接器变更热失效。

**Architecture:** 共用脊（调用无关外圈）放 eval-svc 新包 `internal/metric/fetch/`，HTTP 与 SQL 两 handler 共用。连接器经 `ConnectorDefinitionResolver`（DB 读 + Caffeine，镜像 `DbMetricDefinitionResolver`）解析。kernel 仅加 `MetricFetchError` enum、`MetricValue.reason`、`MetricQuery.subjectAttributes`（纯数据，守纯净）。失效复用 `@ApplicationModuleListener` 监听 P1 的 `ConnectorChangedEvent`。

**Tech Stack:** Java 25 / Spring Boot 4 / Spring Modulith / Caffeine / java.net.http / MyBatis-Plus / WireMock（test）/ JUnit5 + AssertJ。

**前置：** P1 已合（`connector_definition` 表、`config.api.connector.ConnectorDescriptor` 套件、`ConnectorChangedEvent`）。本计划复用 `config.api.connector` 的 descriptor record（不重复定义；eval-svc 本就依赖 config api 模块）。

**范围红线：** 不做 `:test` 端点 / conformance（P3）；不做前端（P4）；AI/ML、CEP 不实现（演进位）。

**环境：** `mvn-env` 设环境后用 `$MVN`，**跨模块带 `-am`**，结束 `$MVN clean test` 兜底。测试方法名英文，注释中文。

---

## 文件结构

**Modify（kernel）：**
- `rule-kernel/.../api/model/MetricValue.java` — 加 `reason` 字段 + 保旧构造器
- `rule-kernel/.../api/model/MetricQuery.java` — 加 `subjectAttributes`（Map，开放结构合规例外）
- **Create** `rule-kernel/.../api/model/MetricFetchError.java`

**Create（eval-svc 共用脊）`rule-eval-svc/.../internal/metric/fetch/`：**
- `VariableRenderer.java` `MetricFetchErrorMapper.java` `ResiliencePolicyExecutor.java` `FetchTrace.java`

**Create（eval-svc 连接器解析）：**
- `rule-eval-svc/.../internal/domain/ConnectorDefinitionRow.java`
- `rule-eval-svc/.../internal/repository/ConnectorDefinitionReadMapper.java`
- `rule-eval-svc/.../internal/metric/http/ConnectorDefinitionResolver.java`

**Create/Rewrite（eval-svc handler）：**
- `rule-eval-svc/.../internal/metric/http/DeclarativeHttpConnectorHandler.java`（删 `ExternalHttpMetricSourceHandler.java`）
- **Modify** `rule-eval-svc/.../internal/metric/sql/SqlAggregateMetricSourceHandler.java`

**Create（eval-svc 失效）：**
- `rule-eval-svc/.../internal/listener/ConnectorIndexEventListener.java`

**Modify（跨模块 connector 引用校验）：**
- `rule-config-svc/.../api/spi/MetricResourceCatalog.java`（加 `connectorNames()`）
- `rule-eval-svc/.../internal/metric/RegistryMetricResourceCatalog.java`（实现 `connectorNames()`）
- `rule-config-svc/.../internal/publish/MetricSafetyValidator.java`（EXTERNAL_HTTP 校验 `params.connector` 闭合）
- `rule-eval-svc/.../internal/assembler/EvalContextAssembler.java`（MetricQuery 注 subjectAttributes + subject.* 选择性时序）

---

## Task 1: kernel 地基（MetricFetchError + MetricValue.reason + MetricQuery.subjectAttributes）

**Files:**
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/MetricFetchError.java`
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/MetricValue.java`
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/MetricQuery.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/MetricFetchErrorTest.java`

参考：`MetricValue.java`（现有 4 参 record + 静态 `error(...)`）；`RuleKind.java`（enum + `tag()` 范式）；memory「Jackson3 primitive 反序列化」（新增字段 grep 孪生 + 真 JSON 测试）。

- [ ] **Step 1: 写 MetricFetchError enum**

```java
package com.sstlfsj.rule.kernel.api.model;

/**
 * 取数失败细码（跨源，可观测用）。降级行为不变——引用该 metric 的条件仍不命中（D15），
 * 细码只进 MetricValue.errorCode + 指标标签。EvalErrorCode.METRIC_FETCH_FAIL 仍为语义层伞码。
 */
public enum MetricFetchError {
    NOT_FOUND, TIMEOUT, UNAUTHORIZED, UPSTREAM_ERROR, PARSE_ERROR, MAPPING_ERROR, TYPE_MISMATCH;

    /** @return 落 MetricValue.errorCode 的字符串标签（== 枚举名）。 */
    public String tag() {
        return name();
    }
}
```

- [ ] **Step 2: 加 MetricValue.reason（保旧构造器）**

打开 `MetricValue.java`：record 头加 `String reason`（末位）。现有便利构造器（3 参/`error(...)`）转调新构造时 `reason=null`。例：
```java
public record MetricValue(Object value, String dataType, String valueSource, String errorCode, String reason) {
    public MetricValue(Object value, String dataType, String valueSource, String errorCode) {
        this(value, dataType, valueSource, errorCode, null);
    }
    public MetricValue(Object value, String dataType, String valueSource) {
        this(value, dataType, valueSource, null, null);
    }
    public static MetricValue error(String errorCode) {
        return new MetricValue(null, DataType.UNKNOWN.tag(), ValueSource.FETCHED.tag(), errorCode, null);
    }
    public static MetricValue error(EvalErrorCode errorCode) { return error(errorCode.name()); }
    public boolean isError() { return errorCode != null; }
}
```
`reason` 是对象类型（String），缺键反序列化落 null，无需 `@JsonSetter(nulls=AS_EMPTY)`（那是 primitive 才需要）。

- [ ] **Step 3: 全仓 grep MetricValue 构造点，确认编译**

Run: `grep -rn "new MetricValue(" rule-*/src/main rule-*/src/test`
预期：现有 `new MetricValue(v, dt, vs)` 与 `new MetricValue(v, dt, vs, err)` 调用因便利构造器保留而**仍编译**。逐一确认无 5 参缺省导致的歧义。

- [ ] **Step 4: 加 MetricQuery.subjectAttributes**

打开 `MetricQuery.java` 确认现有字段（如 `metricCode/params/subjectId/tenantId/eventPayload/now`）。末位加 `Map<String,Object> subjectAttributes`（主体属性，开放异构——同 `Subject.attributes`，CLAUDE.md 合规例外）。加保旧构造器：旧构造转调新构造时 `subjectAttributes=Map.of()`。全仓 grep `new MetricQuery(` 确认构造点（主要在 `EvalContextAssembler` 与各 handler 测试），旧调用经便利构造器仍编译。

- [ ] **Step 5: 写 enum 测试**

```java
package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MetricFetchErrorTest {
    @Test
    void tagEqualsName() {
        assertThat(MetricFetchError.TIMEOUT.tag()).isEqualTo("TIMEOUT");
    }
    @Test
    void coversSevenCodes() {
        assertThat(MetricFetchError.values()).hasSize(7);
    }
}
```

- [ ] **Step 6: 跑 kernel 全量（含现有 MetricValue/MetricQuery 序列化测试）**

Run: `$MVN -pl rule-kernel test`
Expected: PASS。**红旗**：若现有 snapshot/trace JSON 测试因 MetricValue 多字段失败，检查是否有处用位置参数全量序列化断言——按需更新断言（reason 多出 null 字段）。

- [ ] **Step 7: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/MetricFetchError.java \
        rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/MetricValue.java \
        rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/MetricQuery.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/MetricFetchErrorTest.java
git commit -m "feat(kernel): MetricFetchError 细码 + MetricValue.reason + MetricQuery.subjectAttributes"
```

---

## Task 2: VariableRenderer（共用脊·命名空间渲染）

**Files:**
- Create: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/metric/fetch/VariableRenderer.java`
- Test: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/metric/fetch/VariableRendererTest.java`

抽自现有 `ExternalHttpMetricSourceHandler.renderPath`（`{x}` 占位）与 `SqlAggregateMetricSourceHandler.bind`（`:x` 命名参数）的命名空间解析。两者渲染目标不同，但 `resolve(namespace, key, ctx)` 共用。

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.eval.internal.metric.fetch;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class VariableRendererTest {

    private final VariableRenderer renderer = new VariableRenderer();

    private VariableRenderer.Context ctx() {
        return new VariableRenderer.Context(
                "sub1", "t1", Instant.parse("2026-06-01T00:00:00Z"),
                Map.of("ip", "1.2.3.4"),          // payload
                Map.of("q", "v"),                  // params
                Map.of("uid", "u9"),               // vars
                Map.of("level", "VIP"));           // subjectAttributes
    }

    @Test
    void rendersBracePlaceholdersWithUrlEncoding() {
        String out = renderer.renderTemplate("/u/{payload.ip}/{vars.uid}/{subject.level}", ctx());
        assertThat(out).isEqualTo("/u/1.2.3.4/u9/VIP");
    }

    @Test
    void resolvesEachNamespace() {
        VariableRenderer.Context c = ctx();
        assertThat(renderer.resolve("payload", "ip", c)).isEqualTo("1.2.3.4");
        assertThat(renderer.resolve("params", "q", c)).isEqualTo("v");
        assertThat(renderer.resolve("vars", "uid", c)).isEqualTo("u9");
        assertThat(renderer.resolve("subject", "level", c)).isEqualTo("VIP");
        assertThat(renderer.resolve("subjectId", null, c)).isEqualTo("sub1");
        assertThat(renderer.resolve("tenantId", null, c)).isEqualTo("t1");
    }

    @Test
    void referencesSubjectDetectsSubjectNamespace() {
        assertThat(renderer.referencesSubject("/a/{subject.level}")).isTrue();
        assertThat(renderer.referencesSubject("/a/{payload.ip}")).isFalse();
    }
}
```

- [ ] **Step 2: 跑确认失败**

Run: `$MVN -pl rule-eval-svc test -Dtest=VariableRendererTest`
Expected: FAIL（类不存在）

- [ ] **Step 3: 写 VariableRenderer**

```java
package com.sstlfsj.rule.eval.internal.metric.fetch;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 跨源变量命名空间渲染（共用脊·调用无关）。命名空间：payload/params/vars/subject/now/subjectId/tenantId。
 * HTTP 用 {x} 占位（renderTemplate，含 URL 编码）；SQL 用 :x 命名参数（resolve 取值，SQL handler 自绑）。
 */
public class VariableRenderer {

    private static final Pattern PH = Pattern.compile("\\{([a-zA-Z_][\\w.]*)}");

    /**
     * 渲染上下文。
     *
     * @param subjectId         主体 id
     * @param tenantId          租户 id
     * @param now               引擎统一时钟
     * @param payload           事件 payload
     * @param params            metric.params.params 子 map
     * @param vars              metric.params.vars（连接器入参）
     * @param subjectAttributes 主体属性（来自 Subject）
     */
    public record Context(String subjectId, String tenantId, Instant now,
                          Map<String, Object> payload, Map<String, Object> params,
                          Map<String, Object> vars, Map<String, Object> subjectAttributes) {}

    /** 渲染含 {ns.key} 占位的模板，逐段 URL 编码（供 HTTP path/query/header/body）。 */
    public String renderTemplate(String template, Context ctx) {
        Matcher m = PH.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String[] parts = m.group(1).split("\\.", 2);
            Object v = resolve(parts[0], parts.length == 2 ? parts[1] : null, ctx);
            String enc = URLEncoder.encode(String.valueOf(v), StandardCharsets.UTF_8).replace("+", "%20");
            m.appendReplacement(out, Matcher.quoteReplacement(enc));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** 解析单个命名空间引用值；未知命名空间/缺键返回 null。 */
    public Object resolve(String namespace, String key, Context ctx) {
        return switch (namespace) {
            case "payload" -> ctx.payload().get(key);
            case "params" -> ctx.params().get(key);
            case "vars" -> ctx.vars().get(key);
            case "subject" -> ctx.subjectAttributes().get(key);
            case "subjectId" -> ctx.subjectId();
            case "tenantId" -> ctx.tenantId();
            case "now" -> ctx.now();
            default -> null;
        };
    }

    /** 模板是否引用了 subject.* 命名空间（供 assembler 判定是否需等 subject 加载）。 */
    public boolean referencesSubject(String template) {
        Matcher m = PH.matcher(template);
        while (m.find()) {
            if (m.group(1).startsWith("subject.")) return true;
        }
        return false;
    }
}
```

- [ ] **Step 4: 跑确认通过 → Commit**

Run: `$MVN -pl rule-eval-svc test -Dtest=VariableRendererTest`（PASS）
```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/metric/fetch/VariableRenderer.java \
        rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/metric/fetch/VariableRendererTest.java
git commit -m "feat(eval): VariableRenderer 跨源命名空间渲染（共用脊）"
```

---

## Task 3: MetricFetchErrorMapper（共用脊·错误归一）

**Files:**
- Create: `rule-eval-svc/.../internal/metric/fetch/MetricFetchErrorMapper.java`
- Test: `rule-eval-svc/.../internal/metric/fetch/MetricFetchErrorMapperTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.eval.internal.metric.fetch;

import com.sstlfsj.rule.kernel.api.model.MetricFetchError;
import org.junit.jupiter.api.Test;
import java.net.http.HttpTimeoutException;
import static org.assertj.core.api.Assertions.assertThat;

class MetricFetchErrorMapperTest {

    private final MetricFetchErrorMapper mapper = new MetricFetchErrorMapper();

    @Test
    void timeoutExceptionMapsToTimeout() {
        assertThat(mapper.fromException(new HttpTimeoutException("t"))).isEqualTo(MetricFetchError.TIMEOUT);
    }

    @Test
    void genericExceptionMapsToUpstreamError() {
        assertThat(mapper.fromException(new RuntimeException("x"))).isEqualTo(MetricFetchError.UPSTREAM_ERROR);
    }

    @Test
    void httpStatusNon2xxMapsToUpstreamError() {
        assertThat(mapper.fromHttpStatus(503)).isEqualTo(MetricFetchError.UPSTREAM_ERROR);
        assertThat(mapper.fromHttpStatus(401)).isEqualTo(MetricFetchError.UNAUTHORIZED);
    }
}
```

- [ ] **Step 2: 跑确认失败 → 写实现**

```java
package com.sstlfsj.rule.eval.internal.metric.fetch;

import com.sstlfsj.rule.kernel.api.model.MetricFetchError;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

/** 把取数异常/状态归一到 MetricFetchError（共用脊·调用无关）。降级行为不变，仅细化可观测码。 */
public class MetricFetchErrorMapper {

    /** 异常 → 细码。 */
    public MetricFetchError fromException(Throwable t) {
        if (t instanceof HttpTimeoutException || t instanceof TimeoutException) return MetricFetchError.TIMEOUT;
        return MetricFetchError.UPSTREAM_ERROR;
    }

    /** HTTP 状态 → 细码（401/403→UNAUTHORIZED，其余非 2xx→UPSTREAM_ERROR）。 */
    public MetricFetchError fromHttpStatus(int status) {
        if (status == 401 || status == 403) return MetricFetchError.UNAUTHORIZED;
        return MetricFetchError.UPSTREAM_ERROR;
    }
}
```

- [ ] **Step 3: 跑通 → Commit**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/metric/fetch/MetricFetchErrorMapper.java \
        rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/metric/fetch/MetricFetchErrorMapperTest.java
git commit -m "feat(eval): MetricFetchErrorMapper 错误归一（共用脊）"
```

---

## Task 4: ResiliencePolicyExecutor（共用脊·弹性）

**Files:**
- Create: `rule-eval-svc/.../internal/metric/fetch/ResiliencePolicyExecutor.java`
- Test: `rule-eval-svc/.../internal/metric/fetch/ResiliencePolicyExecutorTest.java`

最小手写：超时由调用方（HttpClient/statement）设；本执行器负责 retry（按 RetryTrigger）+ 简易熔断（失败率窗口）。不引 Resilience4j。

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.eval.internal.metric.fetch;

import com.sstlfsj.rule.config.api.connector.ResiliencePolicy;
import com.sstlfsj.rule.config.api.connector.RetryTrigger;
import org.junit.jupiter.api.Test;

import java.net.http.HttpTimeoutException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResiliencePolicyExecutorTest {

    private final ResiliencePolicyExecutor exec = new ResiliencePolicyExecutor();

    private ResiliencePolicy policy(int retries) {
        return ResiliencePolicy.builder()
                .connectTimeoutMs(200).readTimeoutMs(300).retries(retries)
                .retryOn(Set.of(RetryTrigger.TIMEOUT)).circuitBreaker(null).build();
    }

    @Test
    void retriesOnTimeoutUpToLimit() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> exec.execute(policy(1), () -> {
            calls.incrementAndGet();
            throw new HttpTimeoutException("t");
        })).isInstanceOf(HttpTimeoutException.class);
        assertThat(calls.get()).isEqualTo(2); // 首次 + 1 重试
    }

    @Test
    void doesNotRetryNonMatchingError() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> exec.execute(policy(2), () -> {
            calls.incrementAndGet();
            throw new RuntimeException("non-timeout");
        })).isInstanceOf(RuntimeException.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void returnsValueOnSuccess() throws Exception {
        assertThat(exec.execute(policy(1), () -> 42)).isEqualTo(42);
    }
}
```

- [ ] **Step 2: 跑确认失败 → 写实现**

```java
package com.sstlfsj.rule.eval.internal.metric.fetch;

import com.sstlfsj.rule.config.api.connector.ResiliencePolicy;
import com.sstlfsj.rule.config.api.connector.RetryTrigger;

import java.net.http.HttpTimeoutException;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;

/**
 * 最小弹性执行器（共用脊）：按 RetryTrigger 重试。超时由调用方设（HttpClient/statement），
 * 不引 Resilience4j。熔断（CircuitBreakerPolicy）作后续增强位，v1 仅 retry。
 */
public class ResiliencePolicyExecutor {

    /**
     * 按策略执行取数动作，可重试。
     *
     * @param policy 弹性策略
     * @param action 取数动作（抛异常表失败）
     * @return 动作结果
     * @throws Exception 重试耗尽后的最后一次异常
     */
    public <T> T execute(ResiliencePolicy policy, Callable<T> action) throws Exception {
        int attempts = Math.max(0, policy.retries()) + 1;
        Exception last = null;
        for (int i = 0; i < attempts; i++) {
            try {
                return action.call();
            } catch (Exception e) {
                last = e;
                if (i == attempts - 1 || !retryable(e, policy)) throw e;
            }
        }
        throw last;
    }

    private boolean retryable(Exception e, ResiliencePolicy policy) {
        if (policy.retryOn() == null) return false;
        boolean timeout = e instanceof HttpTimeoutException || e instanceof TimeoutException;
        return timeout && policy.retryOn().contains(RetryTrigger.TIMEOUT);
    }
}
```

- [ ] **Step 3: 跑通 → Commit**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/metric/fetch/ResiliencePolicyExecutor.java \
        rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/metric/fetch/ResiliencePolicyExecutorTest.java
git commit -m "feat(eval): ResiliencePolicyExecutor 最小弹性（共用脊）"
```

---

## Task 5: FetchTrace record（共用脊·分阶段 trace）

**Files:**
- Create: `rule-eval-svc/.../internal/metric/fetch/FetchTrace.java`

> P3 的 `:test` 端点产出此结构；P2 先定义供 handler 可选填充。放 eval-svc internal；P3 若需跨模块出 api 再上移（届时决定）。

- [ ] **Step 1: 写 record**

```java
package com.sstlfsj.rule.eval.internal.metric.fetch;

/**
 * 取数分阶段 trace（自助测试用）。HTTP：renderedRequest/rawResponse/successMatched/mappedValue/errorCode；
 * SQL：boundSql/rawFirstRow/coercedValue/errorCode。按源填相应字段，未用字段为 null。
 *
 * @param sourceType      源类型
 * @param renderedRequest HTTP 渲染后请求（method url headers body 文本）
 * @param boundSql        SQL 绑定后语句
 * @param rawResponse     HTTP 原始响应体 / SQL 原始首行文本
 * @param successMatched  HTTP successWhen 判定结果
 * @param mappedValue     映射/强转后的值
 * @param errorCode       命中的 MetricFetchError 名，成功为 null
 */
public record FetchTrace(
        String sourceType,
        String renderedRequest,
        String boundSql,
        String rawResponse,
        Boolean successMatched,
        Object mappedValue,
        String errorCode) {}
```

- [ ] **Step 2: 编译 → Commit**

Run: `$MVN -pl rule-eval-svc test-compile`
```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/metric/fetch/FetchTrace.java
git commit -m "feat(eval): FetchTrace 分阶段 trace record（共用脊）"
```

---

## Task 6: ConnectorDefinitionResolver（eval 读 + Caffeine）

**Files:**
- Create: `rule-eval-svc/.../internal/domain/ConnectorDefinitionRow.java`
- Create: `rule-eval-svc/.../internal/repository/ConnectorDefinitionReadMapper.java`
- Create: `rule-eval-svc/.../internal/metric/http/ConnectorDefinitionResolver.java`
- Test: `rule-eval-svc/.../internal/metric/http/ConnectorDefinitionResolverTest.java`

镜像 `rule-eval-svc/.../internal/metric/DbMetricDefinitionResolver.java`（DB 读 + Caffeine）+ 其 read mapper/row。**先打开 `DbMetricDefinitionResolver` 与对应 `MetricDefinitionReadMapper`/`MetricDefinitionRow` 照抄结构**（含 Caffeine 构建、缓存键、`@TableField(typeHandler=Jackson3TypeHandler.class)` 读 descriptor）。

- [ ] **Step 1: 写 Row（读实体，typed descriptor 列）**

```java
package com.sstlfsj.rule.eval.internal.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;
import com.sstlfsj.rule.config.api.connector.ConnectorDescriptor;
import lombok.Getter;
import lombok.Setter;

/** 连接器读视图（eval 侧，只读 connector_definition）。 */
@Getter
@Setter
@TableName(value = "connector_definition", autoResultMap = true)
public class ConnectorDefinitionRow {
    private Long id;
    private Long tenantId;
    private String connectorCode;
    private String status;
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private ConnectorDescriptor descriptor;
}
```

- [ ] **Step 2: 写 ReadMapper（default 查询）**

```java
package com.sstlfsj.rule.eval.internal.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.eval.internal.domain.ConnectorDefinitionRow;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** 连接器只读查询（eval 侧）。 */
@Mapper
public interface ConnectorDefinitionReadMapper extends BaseMapper<ConnectorDefinitionRow> {

    /** 取租户内某 ACTIVE 连接器，null 表示不存在。 */
    default ConnectorDefinitionRow findActive(Long tenantId, String connectorCode) {
        return selectOne(new LambdaQueryWrapper<ConnectorDefinitionRow>()
                .eq(ConnectorDefinitionRow::getTenantId, tenantId)
                .eq(ConnectorDefinitionRow::getConnectorCode, connectorCode)
                .eq(ConnectorDefinitionRow::getStatus, "ACTIVE"));
    }

    /** 取租户内全部 ACTIVE 连接器编码（供发布期引用闭合校验）。 */
    default List<String> findActiveCodes(Long tenantId) {
        return selectList(new LambdaQueryWrapper<ConnectorDefinitionRow>()
                .select(ConnectorDefinitionRow::getConnectorCode)
                .eq(ConnectorDefinitionRow::getTenantId, tenantId)
                .eq(ConnectorDefinitionRow::getStatus, "ACTIVE"))
                .stream().map(ConnectorDefinitionRow::getConnectorCode).toList();
    }
}
```

- [ ] **Step 3: 写 Resolver（Caffeine，镜像 DbMetricDefinitionResolver）**

照 `DbMetricDefinitionResolver` 的 Caffeine 构建（`maximumSize` + `expireAfterWrite`，TTL 取与 metric resolver 一致如 60s 或读 properties）。键 `tenantId:connectorCode`。提供 `resolve(tenantId, code)→ConnectorDescriptor`（缺失返 null）+ `invalidate(tenantId, code)`（供失效监听器）。`@Component`，注入 `ConnectorDefinitionReadMapper`。中文 Javadoc。

```java
package com.sstlfsj.rule.eval.internal.metric.http;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sstlfsj.rule.config.api.connector.ConnectorDescriptor;
import com.sstlfsj.rule.eval.internal.domain.ConnectorDefinitionRow;
import com.sstlfsj.rule.eval.internal.repository.ConnectorDefinitionReadMapper;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/** 连接器描述符解析（DB 读 + Caffeine，热路径不直查库；镜像 DbMetricDefinitionResolver）。 */
@Component
public class ConnectorDefinitionResolver {

    private final ConnectorDefinitionReadMapper mapper;
    private final Cache<String, Optional<ConnectorDescriptor>> cache = Caffeine.newBuilder()
            .maximumSize(2_000).expireAfterWrite(Duration.ofSeconds(60)).build();

    public ConnectorDefinitionResolver(ConnectorDefinitionReadMapper mapper) {
        this.mapper = mapper;
    }

    /** 解析连接器描述符，缺失返 null（缓存负结果避免穿透）。 */
    public ConnectorDescriptor resolve(Long tenantId, String connectorCode) {
        return cache.get(tenantId + ":" + connectorCode, k -> {
            ConnectorDefinitionRow row = mapper.findActive(tenantId, connectorCode);
            return Optional.ofNullable(row == null ? null : row.getDescriptor());
        }).orElse(null);
    }

    /** 失效指定连接器缓存（供 ConnectorChangedEvent 监听器）。 */
    public void invalidate(Long tenantId, String connectorCode) {
        cache.invalidate(tenantId + ":" + connectorCode);
    }
}
```

- [ ] **Step 4: 写解析测试（mock mapper）**

```java
// ConnectorDefinitionResolverTest：mock ConnectorDefinitionReadMapper，
// 验 resolve 命中缓存（第二次不再查库）、invalidate 后重查、缺失返 null。照 DbMetricDefinitionResolver 测试范式（若有）。
```

- [ ] **Step 5: 跑通 → Commit**

Run: `$MVN -pl rule-eval-svc test -Dtest=ConnectorDefinitionResolverTest`
```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/domain/ConnectorDefinitionRow.java \
        rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/repository/ConnectorDefinitionReadMapper.java \
        rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/metric/http/ConnectorDefinitionResolver.java \
        rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/metric/http/ConnectorDefinitionResolverTest.java
git commit -m "feat(eval): ConnectorDefinitionResolver（DB 读 + Caffeine）"
```

---

## Task 6B: 鉴权基础设施（CredentialStore + OAuth2TokenManager）

> 连接器 auth 三种 kind 的密钥都以 `*Ref` 引用 infra 凭证（不内联明文）。需要一个凭证库把 ref→secret 解析（来自配置 env/secrets），再给 OAUTH2_CLIENT_CREDENTIALS 加 token 交换+缓存。STATIC_HEADER/BEARER/OAUTH2 三种都用 CredentialStore；OAuth2 额外用 OAuth2TokenManager。

**Files:**
- Modify: `rule-eval-svc/.../internal/metric/sql/FetchResourceProperties.java`（加 credentials 配置块）
- Create: `rule-eval-svc/.../internal/metric/http/CredentialStore.java`
- Create: `rule-eval-svc/.../internal/metric/http/OAuth2TokenManager.java`
- Test: `CredentialStoreTest.java`、`OAuth2TokenManagerTest.java`（WireMock token 端点）

参考：`FetchResourceProperties`（现有 endpoints/datasources 配置块范式）、`HttpEndpointRegistry`（从 properties 建 bean）。

- [ ] **Step 1: FetchResourceProperties 加 credentials 配置块**

打开 `FetchResourceProperties` 核对现有结构（`@ConfigurationProperties` 前缀、endpoints/datasources 列表范式），照同款加 `List<CredentialDef> credentials`，`CredentialDef{String name; String value;}`（值来自 env/secrets，不落 metric/connector）。中文 Javadoc。

- [ ] **Step 2: CredentialStore + 测试**

```java
// CredentialStore：@Component，构造从 FetchResourceProperties.getCredentials() 建 Map<name,value>。
// get(String ref) → String（缺失返 null）。中文 Javadoc。
// CredentialStoreTest：配 credentials [{name:"risk-cid",value:"abc"}] → get("risk-cid")=="abc"、get("ghost")==null。
```

- [ ] **Step 3: OAuth2TokenManager 失败测试（WireMock token 端点）**

```java
// OAuth2TokenManagerTest：
// - WireMock stub POST /token 返回 {"access_token":"tok-1","expires_in":3600}
// - CredentialStore 桩：clientIdRef→"cid", clientSecretRef→"sec"
// - auth = OAuth2ClientCredentialsAuth(tokenUrl=http://localhost:{port}/token, clientIdRef=cid-ref, clientSecretRef=sec-ref, scopes=[score])
// 断言 1：token(auth) == "tok-1"
// 断言 2：连续两次 token(auth) 命中缓存，WireMock 只收到 1 次 /token 请求（verify exactly 1）
// 断言 3：缺凭证（store 返 null）→ 抛异常或返回可识别失败（handler 据此归 UNAUTHORIZED）
```

- [ ] **Step 4: 写 OAuth2TokenManager**

```java
// @Component，注入 CredentialStore + HttpClient（自建，连接/读超时合理值如 2s/5s——token 交换不在评估热路径预算内，但仍设超时）。
// token(OAuth2ClientCredentialsAuth auth) → String：
//   key = auth.tokenUrl()+"|"+auth.clientIdRef()+"|"+String.join(",",scopes)
//   命中缓存且未过期 → 返缓存 access_token
//   否则：clientId=store.get(clientIdRef)、secret=store.get(clientSecretRef)（任一 null → 抛 CredentialMissingException）；
//     POST tokenUrl，body=form "grant_type=client_credentials&scope=..."，Authorization: Basic base64(clientId:secret)；
//     解析 JSON access_token + expires_in（tools.jackson）；缓存（过期 = now + expires_in - 30s 安全边际）；返 token。
//   线程安全：ConcurrentHashMap + computeIfAbsent/显式过期判断（避免并发重复换取，简单做法即可）。
// 注意：now 用 Instant.now()（token 缓存过期判断属基础设施时钟，非引擎统一时钟；不要硬塞 MetricQuery.now）。
```

- [ ] **Step 5: 跑通 → Commit**

Run: `$MVN -pl rule-eval-svc test -Dtest=CredentialStoreTest,OAuth2TokenManagerTest`
```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/metric/sql/FetchResourceProperties.java \
        rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/metric/http/CredentialStore.java \
        rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/metric/http/OAuth2TokenManager.java \
        rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/metric/http/CredentialStoreTest.java \
        rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/metric/http/OAuth2TokenManagerTest.java
git commit -m "feat(eval): 连接器鉴权基础设施（CredentialStore + OAuth2 client-credentials token 管理）"
```

---

## Task 7: DeclarativeHttpConnectorHandler（重构 + 删旧）

**Files:**
- Create: `rule-eval-svc/.../internal/metric/http/DeclarativeHttpConnectorHandler.java`
- Delete: `rule-eval-svc/.../internal/metric/http/ExternalHttpMetricSourceHandler.java`
- Test: `rule-eval-svc/.../internal/metric/http/DeclarativeHttpConnectorHandlerTest.java`（WireMock）
- Delete: 旧 `ExternalHttpMetricSourceHandlerTest.java`

现有 `HttpEndpointRegistry`（传输层 baseUrl/auth/client）保留。handler 新流程：`params.connector`→`ConnectorDefinitionResolver.resolve`→`VariableRenderer` 渲染 method/path/query/header/body→应用 `AuthScheme`→`ResiliencePolicyExecutor` 发请求（HttpClient 超时由 ResiliencePolicy）→`ResponseMapping.successWhen` 判定→`valuePath` 取值→`DataTypeCoercion.coerce`→失败经 `MetricFetchErrorMapper` 归一。`@Component @MetricSourceType(SourceType.EXTERNAL_HTTP)`（删旧 handler 后此 sourceType 唯一）。

> greenfield 无存量 metric 数据（memory「Greenfield Dev Phase」），EXTERNAL_HTTP metric 的 `params` 从 `{endpoint,path,jsonPath}` 直接切到 `{connector,vars}`，不留兼容分支。

- [ ] **Step 1: 删旧 handler 及其测试**

```bash
git rm rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/metric/http/ExternalHttpMetricSourceHandler.java \
       rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/metric/http/ExternalHttpMetricSourceHandlerTest.java
```

- [ ] **Step 2: 写 WireMock 失败测试**

确认 eval-svc test 依赖含 WireMock（根 pom `wiremock.version`；eval-svc pom 若无则加 test scope 坐标，不写 version——根 dependencyManagement 已管）。

```java
// DeclarativeHttpConnectorHandlerTest：
// - WireMockServer 起桩上游，stub POST /score/sub1 返回 {"code":0,"data":{"score":88}}
// - HttpEndpointRegistry 注册名 "risk" → baseUrl = http://localhost:{wiremockPort}
// - ConnectorDefinitionResolver mock 返回 descriptor(endpointRef=risk, POST /score/{subjectId},
//     successWhen code EQ 0, valuePath data.score, BearerAuth)
// - 构造 MetricQuery(params={connector:"risk-svc"}, subjectId=sub1, ...)
// 断言 1：成功 → MetricValue.value == 88, valueSource FETCHED, errorCode null
// 断言 2：上游返回 500 → MetricValue.isError(), errorCode == "UPSTREAM_ERROR"
// 断言 3：successWhen 不命中（code=1）→ errorCode == "UPSTREAM_ERROR"（无 errorMapping 命中时默认）
// 断言 4：valuePath 未命中 → errorCode == "PARSE_ERROR"
// 断言 5（OAuth2）：descriptor.auth = OAuth2ClientCredentialsAuth + OAuth2TokenManager 桩返 "tok-x"
//     → 上游请求头带 Authorization: Bearer tok-x（WireMock verify），取数成功；凭证缺失 → errorCode == "UNAUTHORIZED"
```

- [ ] **Step 3: 跑确认失败 → 写 handler**

实现 `DeclarativeHttpConnectorHandler`（流程见上）。关键点：
- 从 `query.params().get("connector")` 取连接器名，`query.params().get("vars")` 取 vars map。
- 构造 `VariableRenderer.Context`（payload=query.eventPayload, params=query.params 的 params 子项, vars, subjectAttributes=query.subjectAttributes）。
- 鉴权（注入 `CredentialStore` + `OAuth2TokenManager`，均来自 Task 6B）：`switch (auth.kind())` —— **STATIC_HEADER**：`req.header(headerName, credentialStore.get(credentialRef))`；**BEARER**：`req.header("Authorization", "Bearer " + credentialStore.get(tokenRef))`；**OAUTH2_CLIENT_CREDENTIALS**：`req.header("Authorization", "Bearer " + oauth2TokenManager.token(auth))`。**三种 kind 本任务都必须完整可用**（用户选 A，OAuth2 不暂缓）。任一凭证缺失 / token 交换失败 → 归 `UNAUTHORIZED`（catch 后 `MetricValue.error(UNAUTHORIZED.tag())`，**绝不抛到引擎**）。
- 失败：catch 异常 → `MetricFetchErrorMapper.fromException` → `MetricValue.error(code.tag())`；非 2xx → `fromHttpStatus`；successWhen 不命中 → 查 errorMapping，命中则用其 `to`，否则 UPSTREAM_ERROR；valuePath miss → PARSE_ERROR。**绝不抛到引擎**。

- [ ] **Step 4: 跑通 → Commit**

Run: `$MVN -pl rule-eval-svc test -Dtest=DeclarativeHttpConnectorHandlerTest`
```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/metric/http/DeclarativeHttpConnectorHandler.java \
        rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/metric/http/DeclarativeHttpConnectorHandlerTest.java
git commit -m "feat(eval): DeclarativeHttpConnectorHandler 声明式连接器取数（删旧 EXTERNAL_HTTP handler）"
```

---

## Task 8: SQL handler 继承共用脊 + subject.* 选择性时序

**Files:**
- Modify: `rule-eval-svc/.../internal/metric/sql/SqlAggregateMetricSourceHandler.java`
- Modify: `rule-eval-svc/.../internal/assembler/EvalContextAssembler.java`
- Test: 改 `SqlAggregateMetricSourceHandlerTest.java`

SQL 继承脊（设计 §8）：(1) 错误经 `MetricFetchErrorMapper`；(2) statement 超时——`NamedParameterJdbcTemplate.getJdbcTemplate().setQueryTimeout(秒)` 或在查询时设（从 `FetchResourceProperties`/ResiliencePolicy 读，秒级向上取整）；(3) `:subject.x` 绑定——`bind(...)` 增 subjectAttributes 参数，`:subject.X→:subject_X` 绑 `query.subjectAttributes().get(X)`，复用 `VariableRenderer` 命名空间集合。

subject.* 时序（设计纪律 B）：subject 加载与 metric 取数并行（D25）。SQL/HTTP 若引用 `subject.*`，其 fetch 须在 subject 加载完成后。**先打开 `EvalContextAssembler` 看 `fetchConcurrently`/subject 加载的 CompletableFuture 编排**，按下述选择性时序改：

- [ ] **Step 1: 读 assembler，确认 subject future 与 metric fetch 的编排点**

Run: `grep -n "Subject\|fetchConcurrently\|CompletableFuture\|allOf\|MetricQuery" rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/assembler/EvalContextAssembler.java`

- [ ] **Step 2: 写/改测试（SQL 错误细码 + :subject.x 绑定）**

```java
// SqlAggregateMetricSourceHandlerTest 增：
// - bind(":subject.level") → 含 :subject_level 参数，值取 subjectAttributes.get("level")
// - DB 异常 → MetricValue.error，errorCode == "UPSTREAM_ERROR"
// - 强转失败 → errorCode == "TYPE_MISMATCH"
// 现有命名参数绑定测试保持绿。
```

- [ ] **Step 3: 改 SQL handler（bind 加 subject + 错误归一 + statement 超时）**

在 `bind(...)` 的命名空间 `switch` 加 `case "subject" -> subjectAttributes.get(parts[1])`；`fetch(...)` catch 块改用 `MetricFetchErrorMapper`；查询前设 `setQueryTimeout`。保持"取首行首列 + DataTypeCoercion"不变。

- [ ] **Step 4: 改 assembler——MetricQuery 注 subjectAttributes + subject.* 选择性时序**

构造 `MetricQuery` 时传入 subjectAttributes（subject 加载完成后才有值）。选择性时序：对**引用 subject.* 的 metric**（用 `VariableRenderer.referencesSubject` 判 SQL/连接器模板），其 fetch future 用 `subjectFuture.thenComposeAsync(subj -> fetch(...))`；**不引用 subject.* 的保持与 subject 加载并行**（D25 不回归）。这是设计纪律 B「留依赖就绪余地、不建通用 DAG」。

> **回退**：若 assembler 并发编排改造在本增量内风险过大，可先让**所有** metric fetch 等 subject 加载（subject-then-fetch 串行），牺牲 D25 并行、换简单正确，并在提交说明里标注"subject.* 暂以串行实现，并行优化留后续"。两条路二选一，**不得让 subject.* 静默取不到值**（取不到=红旗）。

- [ ] **Step 5: 跑 eval 全量（带 -am，因依赖 kernel/config 改动）**

Run: `$MVN -pl rule-eval-svc -am test`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/metric/sql/SqlAggregateMetricSourceHandler.java \
        rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/assembler/EvalContextAssembler.java \
        rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/metric/sql/SqlAggregateMetricSourceHandlerTest.java
git commit -m "feat(eval): SQL handler 继承共用脊（错误细码/statement 超时/subject.x）"
```

---

## Task 9: 连接器失效监听 + 发布期引用闭合校验

**Files:**
- Create: `rule-eval-svc/.../internal/listener/ConnectorIndexEventListener.java`
- Modify: `rule-config-svc/.../api/spi/MetricResourceCatalog.java`（加 `connectorNames()`）
- Modify: `rule-eval-svc/.../internal/metric/RegistryMetricResourceCatalog.java`（实现）
- Modify: `rule-config-svc/.../internal/publish/MetricSafetyValidator.java`（EXTERNAL_HTTP 校验 connector 闭合）
- Test: 相应单测

参考失效监听：`rule-eval-svc/.../internal/listener/SceneIndexEventListener.java`（`@ApplicationModuleListener`）。

- [ ] **Step 1: 写失效监听器 + 测试**

```java
package com.sstlfsj.rule.eval.internal.listener;

import com.sstlfsj.rule.config.api.event.ConnectorChangedEvent;
import com.sstlfsj.rule.eval.internal.metric.http.ConnectorDefinitionResolver;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** 连接器变更失效监听（A 类跨模块，提交后异步）。 */
@Component
public class ConnectorIndexEventListener {

    private final ConnectorDefinitionResolver resolver;

    public ConnectorIndexEventListener(ConnectorDefinitionResolver resolver) {
        this.resolver = resolver;
    }

    /** 收到连接器变更 → 失效该连接器缓存。 */
    @ApplicationModuleListener
    public void onConnectorChanged(ConnectorChangedEvent event) {
        resolver.invalidate(Long.valueOf(event.tenantId()), event.connectorCode());
    }
}
```
测试：照 `SceneIndexEventListener` 测试，验调用 `resolver.invalidate(...)`。

- [ ] **Step 2: MetricResourceCatalog 加 connectorNames() + eval 实现**

`MetricResourceCatalog.java` 接口加：
```java
/** @return 已注册（ACTIVE）连接器名集合，供 metric 发布期 params.connector 闭合校验。 */
Set<String> connectorNames();
```
`RegistryMetricResourceCatalog.java` 实现：注入 `ConnectorDefinitionReadMapper`，按当前租户（或全量）返回 `findActiveCodes`。**注意**：该 catalog 现有 `endpointNames()/datasourceNames()` 的租户范围与调用方式——照其实现，connectorNames 同范围。

- [ ] **Step 3: MetricSafetyValidator 校验 connector 闭合**

`MetricSafetyValidator.validate(...)` 的 EXTERNAL_HTTP 分支：原校验 `endpoint` 已注册，**改为**校验 `params.connector` 在 `connectorNames` 中（新增 `Set<String> connectorNames` 入参）。调用点 `PublishService.freezeMetricDeps` 传入 `metricResourceCatalog.connectorNames()`（catalog null 时传 null 跳过）。改 `MetricSafetyValidatorTest` 对应分支。

> greenfield：EXTERNAL_HTTP metric 的 params 已是 `{connector,vars}`，旧 `{endpoint,path,jsonPath}` 校验分支删除。

- [ ] **Step 4: 跑相关模块测试**

Run: `$MVN -pl rule-config-svc -am test`（验 validator + catalog SPI）
Run: `$MVN -pl rule-eval-svc -am test`（验监听器 + catalog 实现）
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: 连接器失效监听 + metric 发布期 connector 引用闭合校验"
```

---

## Task 10: 全量兜底 + 功能 e2e

**Files:** 无新增

- [ ] **Step 1: 全量 clean test**

Run: `$MVN clean test`
Expected: BUILD SUCCESS（kernel/config/eval/api + Modulith 边界 + ArchUnit kernel 纯净全绿）

- [ ] **Step 2: 功能 e2e（CLAUDE.md 功能测试纪律，单测 mock 不掉的真落库/真取数）**

打可执行包起服务（**别用 reactor run 目标**），确认迁移 V1_34 执行。然后：
1. 起一个桩上游（或用真服务）；config 注册 endpoint（指向桩）。
2. `POST /admin/v1/connectors`（建连接器，引用该 endpoint）→ 查 `connector_definition` 真落库（descriptor JSON typed 落库，非占位）。
3. 建引用该 connector 的 EXTERNAL_HTTP metric（`params={connector,vars}`）。
4. 建规则引用该 metric → 发布（验发布期 connector 闭合校验通过）。
5. 评估 → 验取数命中、`MetricValue.value` 正确、失败时 `errorCode` 为细码。
6. 改连接器 → 验 `ConnectorChangedEvent` 触发缓存失效（再次评估取到新描述符）。
7. 清理测试数据，恢复干净基线。

- [ ] **Step 3: 记录（不入库的功能验证结论写提交说明或 docs/99）**

> 本计划无新增文件提交；如功能验证发现缺陷，回到对应 Task 修复并补测。

---

## Self-Review 记录

- **Spec 覆盖**：§3 契约层运行时、§4 解析/失效、§5.1 命名空间、§6 错误细码、§8 SQL 继承脊（statement 超时/错误码/subject.x）、纪律 B（subject.* 选择性时序）。§9（测试端点/conformance）在 P3，前端在 P4。
- **类型一致**：`MetricFetchError.tag()`、`MetricValue(...,reason)`、`MetricQuery(...,subjectAttributes)`、`VariableRenderer.Context(subjectId,tenantId,now,payload,params,vars,subjectAttributes)`、`ResiliencePolicyExecutor.execute(policy, Callable)`、`ConnectorDefinitionResolver.resolve/invalidate`、`MetricResourceCatalog.connectorNames()` 全计划一致。复用 `config.api.connector.*` 描述符。
- **风险**：MetricQuery/MetricValue 新字段的 Jackson 反序列化孪生点（Task1 grep）；assembler 并发编排改造（Task8 给串行回退）；OAuth2 token 交换并发刷新一致性（Task6B 用 ConcurrentHashMap，注意缓存击穿）；凭证从 env/secrets 注入不落库（Task6B）；statement 超时单位（秒）。
- **Jackson3 包**：databind/core 在 `tools.jackson.*`（ObjectMapper/JsonMapper/JsonNode），注解在 `com.fasterxml.jackson.annotation`（tools.jackson.annotation 不存在，误用编译失败）。OAuth2 token 响应解析、MetricValue 等用此。
- **占位符**：resolver/listener/handler 的 Caffeine、WireMock 起桩、assembler 编排均指向具体 exemplar（`DbMetricDefinitionResolver`/`SceneIndexEventListener`/`EvalContextAssembler`）要求执行者打开核对。
