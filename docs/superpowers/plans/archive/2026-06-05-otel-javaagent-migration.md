# 可观测性方案迁移：Spring SDK → OpenTelemetry Java Agent（已评估，暂缓）

> 状态：**暂缓，不实施**（2026-06-05 决定）。维持现有 Spring SDK 方案（D22）。
>
> **暂缓原因：**
> 1. **GraalVM native image 约束（决定性）**：后续可能编译 native image，而 OTel Java Agent
>    **不支持** native image；现有 Spring SDK 方案（`spring-boot-starter-opentelemetry`）支持
>    Spring AOT / native。只要 native image 仍在后续选项里，就不应切 Agent。
> 2. **即时收益小**：redis/rocketmq 尚未接入，当下切 Agent 只多一个 mysql span，
>    不足以抵消替换 D22 + 改启动方式 + 新栈（Spring Boot 4 / JDK 25）agent 兼容试错的成本。
>
> **重启条件**：明确放弃 native image 路线，**且** redis/rocketmq 接入后确需全链路 trace，
> 再重新评估本方案。下文方案与影响面分析保留备用。
>
> 业务方法探针（自定义业务 span）一并搁置。

## Context

D22 当前用的是 **Spring Boot 4 自家 SDK 方案**（`spring-boot-starter-opentelemetry` + Micrometer
Observation bridge + 手动 logback OTLP appender）。它只自动埋点 HTTP 入口，每条 trace 只有一个根
span——看不到 MySQL、未来的 Redis / RocketMQ 调用链。

需求升级：自动覆盖中间件调用链（JDBC/MySQL，未来 Redis / RocketMQ / HTTP client），且要**零代码侵入**。
SDK 方案天生只埋框架内置 observation 点，给不了广覆盖。**OTel Java Agent**（字节码增强，150+ 库自动
埋点）才满足需求。本次把 D22 的 SDK 方案**整体替换**为 Agent 方案。

> **重要影响**：移除 D22 引入的 `spring-boot-starter-opentelemetry`、手动 logback appender、
> `application.yml` 的 `management.opentelemetry.*` 配置——均被 agent 取代。
> `rule-observability` 的业务 trace（`node_trace` 批写 MySQL）与本次无关，不动。

## 本次范围

✅ **做**：
- OTel Java Agent 挂载（Dockerfile）
- 中间件/框架自动埋点（servlet、JDBC/MySQL；未来接入 Redis / RocketMQ 客户端后**自动覆盖，零改动**）
- 三信号（traces + logs + metrics）走 agent OTLP
- 业务代码零改动、零新依赖

❌ **不做（本次）**：
- 业务方法探针（`OTEL_INSTRUMENTATION_METHODS_INCLUDE`）——业务方法未稳定，方法签名/边界可能变，
  现在固化探针配置易失效。留待后续（末节给出做法，届时仅改一行环境变量即可，仍零代码侵入）。

## 关键设计决定

- **纯 Agent，代码零改动**：rule-kernel / rule-eval-svc / 所有业务代码一行不改、一个依赖不加。
  中间件 span 全靠 agent 字节码增强自动产生。
- **metrics 走 agent OTLP**（用户选定：业务 + 基础设施都走）。
  - ⚠️ **验证点**：agent 的 micrometer instrumentation 桥接 Micrometer 全局 registry；需确认
    `RuleMetrics` 自定义指标能被捕获。**回退**：捕获不到则业务指标退回 `/actuator/prometheus`
    被 Prometheus scrape（保留 actuator 端点兜底），agent 仅推基础指标 + traces + logs。
- **agent jar 固定版本**：Dockerfile 构建期从 GitHub releases 下载固定版本（可复现），不用 latest。
- **logs 须显式开**：OTel agent 默认 `logs exporter = none`，须设 `OTEL_LOGS_EXPORTER=otlp`。
  agent 的 logback-mdc instrumentation 注入 MDC key 为 `trace_id` / `span_id`（**下划线**，
  与现 logback pattern 的驼峰 `traceId` 不同，pattern 需同步改）。
- **采样**：本地 `OTEL_TRACES_SAMPLER=always_on` 全采；生产用 `parentbased_traceidratio` + ratio。

## 影响的文件

| 文件 | 改动 |
|------|------|
| `Dockerfile` | 下载 `opentelemetry-javaagent.jar`；ENTRYPOINT 改 `java -javaagent:/otel/agent.jar -jar app.jar` |
| `rule-app/pom.xml` | **移除** `spring-boot-starter-opentelemetry`、`opentelemetry-logback-appender-1.0` |
| `rule-app/.../application.yml` | **移除** `management.opentelemetry.*` + `spring.autoconfigure.exclude`；保留 actuator prometheus 端点作为 metrics 回退兜底 |
| `rule-app/.../logback-spring.xml` | **移除**手动 `OpenTelemetryAppender`（agent 自动注入）；CONSOLE pattern `traceId/spanId` → `trace_id/span_id` |
| `docker-compose.yml` | `rule-app` 环境变量改为 agent 识别的 `OTEL_*`（见下） |
| `docs/07-operability.md §十一` | 整节改写为 Agent 方案 |
| `docs/08-evolution.md §2.22` | 更新实现要点为 Agent |

**业务代码（rule-kernel / rule-eval-svc / rule-api 等）：零改动。**

## docker-compose rule-app 环境变量

```yaml
environment:
  # —— 数据源（不变）——
  SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/rule_engine?...
  SPRING_DATASOURCE_USERNAME: root
  SPRING_DATASOURCE_PASSWORD: root
  # —— OTel Agent 基础 ——
  OTEL_SERVICE_NAME: rule-engine
  OTEL_EXPORTER_OTLP_ENDPOINT: http://otel-lgtm:4318
  OTEL_EXPORTER_OTLP_PROTOCOL: http/protobuf
  # —— 三信号 exporter（logs 默认 none，须显式开）——
  OTEL_TRACES_EXPORTER: otlp
  OTEL_METRICS_EXPORTER: otlp
  OTEL_LOGS_EXPORTER: otlp
  OTEL_TRACES_SAMPLER: always_on
  # 注意：本次不加 OTEL_INSTRUMENTATION_METHODS_INCLUDE（业务探针留待后续）
```

## Dockerfile（参考形态）

```dockerfile
FROM eclipse-temurin:25-jre
WORKDIR /app
# 固定版本 agent，构建期下载（版本号执行时取当前最新稳定 2.x）
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.<X>.0/opentelemetry-javaagent.jar /otel/agent.jar
COPY rule-app/target/rule-app-*.jar app.jar
ENTRYPOINT ["java", "-javaagent:/otel/agent.jar", "-jar", "app.jar"]
```

## 执行顺序（待批准后）

1. 改 Dockerfile（挂 agent + 下载 jar）
2. rule-app/pom.xml 移除两个 OTel 依赖
3. application.yml 移除 `management.opentelemetry.*` + `autoconfigure.exclude`
4. logback-spring.xml 移除手动 appender + MDC key 改下划线
5. docker-compose.yml 换 agent 环境变量
6. `$MVN package -DskipTests -pl rule-app -am` 重新打包
7. `docker compose down && docker compose up -d --build` 重启
8. 三信号验证（见下）
9. 文档更新（07 §十一、08 §2.22）
10. commit

## 验证

```bash
$MVN package -DskipTests -pl rule-app -am
docker compose down --remove-orphans && docker compose up -d --build
# 等就绪后发会落 MySQL 的评估请求
curl -X POST http://localhost:8080/api/v1/rule/evaluate -H "Content-Type: application/json" \
  -d '{"tenantId":"1","sceneCode":"smoke.scene","eventType":"order.placed","subjectId":"u1",
       "eventId":"evt-agent-1","occurredAt":"2026-06-05T00:00:00Z","payload":{},
       "providedMetrics":{"order.amount":200}}'
```

**通过标准**：
- **Tempo**：评估 trace 的 span 树为 `http post /api/v1/rule/evaluate`（根）→ 下挂 **MySQL JDBC span**
  （session/trace 落库）。即比现状多出 DB 子 span。
- **Loki**：日志带 `trace_id` 字段，能从日志跳 Tempo。
- **Metrics**：Grafana Explore → Prometheus 查到 `rule_engine_*`（验证 agent 桥接成功）；
  查不到则触发回退（保留 actuator scrape）。
- 业务链路回归无异常（评估 HIT/MISS、查列表、查 session 全部 200）。

## 风险与回退

- **agent 桥接自定义 metrics 不成功** → 保留 `/actuator/prometheus`，业务指标走 Prometheus scrape。
- **agent 与库不兼容**（Spring Boot 4 / JDK 25 较新）→ 降到 agent 上一稳定版本；
  或临时 `OTEL_JAVAAGENT_ENABLED=false` 排除 agent 自身问题。
- **MySQL JDBC span 缺失** → 确认 agent jdbc instrumentation 未被关（默认开），数据源为 Spring 管理
  （HikariCP 标准注入，已满足）。

---

## 后续：业务方法探针（待业务稳定后再加）

业务方法稳定后，**无需改代码、无需加依赖**，仅在 docker-compose 的 rule-app 环境变量追加一行：

```yaml
OTEL_INSTRUMENTATION_METHODS_INCLUDE: "com.sstlfsj.rule.kernel.internal.engine.EvalEngine[evaluate];<其他类>[<方法>]"
```

agent 即对列出的方法自动生成 span（span 名 = 方法名），挂在 HTTP span 下，仍零代码侵入。
- 适用：稳定的业务边界方法（如 `EvalEngine.evaluate`、快照加载、metric 批拉）。
- 局限：span 名固定为方法名，不能挂业务属性（tenantId / ruleHit 等）。若将来需要业务属性，
  再评估引入 `opentelemetry-instrumentation-annotations`（annotation-only 轻量包）用 `@WithSpan` +
  `@SpanAttribute`，那是另一档需求，届时单独规划。
