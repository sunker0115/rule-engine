# 基础设施层可观测性（OTLP + LGTM）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `rule-app` 引入 `spring-boot-starter-opentelemetry`，将 metrics 改为 OTLP push，加分布式 trace 和日志聚合到 Loki，本地 docker-compose 加 `grafana/otel-lgtm`，实现 metrics / traces / logs 三信号统一。

**Architecture:** `rule-app` 加 opentelemetry 自动装配依赖，负责基础设施层（HTTP 请求 trace、JVM metrics 推送、Logback OTLP appender）；`rule-observability` 不改动，继续承担业务层 trace（node_trace → MySQL）。`docker-compose.yml` 加 `grafana/otel-lgtm` 单镜像作为本地 LGTM 后端，接收 OTLP 三信号。

**Tech Stack:** Spring Boot 4.x `spring-boot-starter-opentelemetry`、`grafana/otel-lgtm` Docker 镜像、Logback OTLP appender（`io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0`）

---

## 涉及文件

| 文件 | 操作 | 说明 |
|------|------|------|
| `rule-app/pom.xml` | 修改 | 加 `spring-boot-starter-opentelemetry` 依赖 |
| `rule-app/src/main/resources/application.yml` | 修改 | 替换 Prometheus scrape 为 OTLP push，加 trace / logging 配置 |
| `rule-app/src/main/resources/logback-spring.xml` | 新建 | OTLP appender + 控制台 appender，traceId 自动注入 |
| `docker-compose.yml` | 修改 | 加 `otel-lgtm` service，rule-app 加 OTEL 环境变量 |

---

## Task 1：`rule-app` 加 opentelemetry 依赖

**Files:**
- Modify: `rule-app/pom.xml`

- [ ] **Step 1：在 `rule-app/pom.xml` `<dependencies>` 末尾加依赖**

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-opentelemetry</artifactId>
        </dependency>
```

版本由 `spring-boot-starter-parent 4.0.6` BOM 管理，无需指定。

- [ ] **Step 2：编译验证无报错**

```bash
$MVN -pl rule-app -am compile -q
```

期望：BUILD SUCCESS，无编译错误。

- [ ] **Step 3：提交**

```bash
git add rule-app/pom.xml
git commit -m "feat(observability): rule-app 加 spring-boot-starter-opentelemetry 依赖"
```

---

## Task 2：`application.yml` 配置 OTLP 三信号

**Files:**
- Modify: `rule-app/src/main/resources/application.yml`

> **背景**：当前 `management.metrics.export.prometheus.enabled: true` 走 scrape 模式。改为 OTLP push 后，prometheus 端点仍保留（不删），两者可共存；待 LGTM 稳定后再决定是否关闭 scrape 端点。

- [ ] **Step 1：在 `application.yml` `management:` 块内追加 OTLP 配置**

找到现有 `management:` 块：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,info
  metrics:
    export:
      prometheus:
        enabled: true
```

替换为：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,info
  metrics:
    export:
      prometheus:
        enabled: true
  otlp:
    metrics:
      export:
        url: ${OTEL_EXPORTER_OTLP_METRICS_ENDPOINT:http://localhost:4318/v1/metrics}
        step: 30s
    tracing:
      export:
        url: ${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT:http://localhost:4318/v1/traces}
  tracing:
    sampling:
      probability: 1.0
```

说明：
- `prometheus.enabled: true` 保留，scrape 和 push 共存过渡期
- `probability: 1.0` 本地开发全量采样；生产按需调低（0.1 = 10%）
- 端点通过环境变量覆盖，容器部署时注入 `OTEL_EXPORTER_OTLP_*_ENDPOINT`

- [ ] **Step 2：提交**

```bash
git add rule-app/src/main/resources/application.yml
git commit -m "feat(observability): application.yml 加 OTLP metrics + tracing 配置"
```

---

## Task 3：新建 `logback-spring.xml`（OTLP appender）

**Files:**
- Create: `rule-app/src/main/resources/logback-spring.xml`

> **背景**：Spring Boot 4.x 自动加载 `logback-spring.xml`（优先级高于 `logback.xml`）。加 OTLP appender 后，日志推 Loki，每条日志自动带 `traceId` / `spanId`，可从 Grafana 日志跳转到 Tempo 链路。

- [ ] **Step 1：新建文件**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <include resource="org/springframework/boot/logging/logback/defaults.xml"/>

    <!-- 控制台输出，带 traceId/spanId -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} traceId=%X{traceId} spanId=%X{spanId} - %msg%n</pattern>
        </encoder>
    </appender>

    <!-- OTLP 日志推送到 Loki（通过 OTLP HTTP） -->
    <appender name="OTLP" class="io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender">
        <captureExperimentalAttributes>true</captureExperimentalAttributes>
        <captureKeyValuePairAttributes>true</captureKeyValuePairAttributes>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="OTLP"/>
    </root>

    <!-- rule-engine 包走 DEBUG，与原 application.yml logging.level 对应 -->
    <logger name="com.sstlfsj.rule" level="DEBUG"/>
</configuration>
```

- [ ] **Step 2：在父 `pom.xml` `<dependencyManagement>` 确认 opentelemetry-logback appender 由 BOM 管理**

Spring Boot 4.x `spring-boot-starter-opentelemetry` 传递引入 `opentelemetry-logback-appender-1.0`，无需手动加依赖。验证：

```bash
$MVN -pl rule-app dependency:tree | grep opentelemetry-logback
```

期望输出类似：
```
[INFO] \- io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0:jar:...
```

- [ ] **Step 3：提交**

```bash
git add rule-app/src/main/resources/logback-spring.xml
git commit -m "feat(observability): 新增 logback-spring.xml，加 OTLP log appender"
```

---

## Task 4：`docker-compose.yml` 加 `grafana/otel-lgtm`

**Files:**
- Modify: `docker-compose.yml`

> **背景**：`grafana/otel-lgtm` 是单镜像集成 Grafana + Loki + Tempo + Mimir（Prometheus-compatible），本地开发一个容器接收 OTLP 三信号，端口：`3000`（Grafana UI）、`4317`（gRPC OTLP）、`4318`（HTTP OTLP）。

- [ ] **Step 1：在 `docker-compose.yml` 加 `otel-lgtm` service，并给 `rule-app` 注入 OTEL 环境变量**

在现有 `services:` 末尾追加：

```yaml
  otel-lgtm:
    image: grafana/otel-lgtm:0.8.0
    ports:
      - "3000:3000"   # Grafana UI
      - "4317:4317"   # gRPC OTLP
      - "4318:4318"   # HTTP OTLP
    environment:
      GF_AUTH_ANONYMOUS_ENABLED: "true"
      GF_AUTH_ANONYMOUS_ORG_ROLE: "Admin"
```

同时在 `rule-app` service 的 `environment:` 块追加 OTEL 端点变量：

```yaml
      OTEL_EXPORTER_OTLP_METRICS_ENDPOINT: http://otel-lgtm:4318/v1/metrics
      OTEL_EXPORTER_OTLP_TRACES_ENDPOINT: http://otel-lgtm:4318/v1/traces
      OTEL_EXPORTER_OTLP_LOGS_ENDPOINT: http://otel-lgtm:4318/v1/logs
      OTEL_SERVICE_NAME: rule-engine
```

`rule-app.depends_on` 加 `otel-lgtm`：

```yaml
    depends_on:
      mysql:
        condition: service_healthy
      otel-lgtm:
        condition: service_started
```

- [ ] **Step 2：本地验证 docker-compose 配置正确**

```bash
docker compose config --quiet
```

期望：无报错输出（退出码 0）。

- [ ] **Step 3：提交**

```bash
git add docker-compose.yml
git commit -m "feat(observability): docker-compose 加 grafana/otel-lgtm，rule-app 注入 OTEL 端点"
```

---

## Task 5：本地冒烟验证

> 此 Task 是手动验证步骤，不产生代码提交。

- [ ] **Step 1：启动全栈**

```bash
docker compose up -d
```

等待所有容器 healthy，通常 30–60 秒。

- [ ] **Step 2：打一次评估请求**

```bash
curl -s -o /dev/null -w "%{http_code}" \
  -X POST http://localhost:8080/api/v1/evaluate \
  -H "Content-Type: application/json" \
  -d '{"tenantId":"smoke","sceneCode":"test","eventId":"e1","subjectId":"u1","payload":{}}'
```

期望 HTTP 200 或 400（规则不存在也行，目的是产生一次 HTTP trace）。

- [ ] **Step 3：打开 Grafana 验证三信号**

访问 `http://localhost:3000`（默认 admin/admin 或匿名）：

1. Explore → 选 Tempo → 搜索 `service.name = rule-engine`，应出现刚才请求的 span
2. Explore → 选 Loki → 查询 `{service_name="rule-engine"}`，应出现日志条目且含 traceId
3. Explore → 选 Prometheus → 查询 `rule_engine_eval_total`，应出现业务指标

- [ ] **Step 4：停止容器**

```bash
docker compose down
```

---

## 验证命令汇总

```bash
# 编译
$MVN -pl rule-app -am compile -q

# 查看 opentelemetry-logback appender 传递依赖
$MVN -pl rule-app dependency:tree | grep opentelemetry-logback

# docker-compose 配置校验
docker compose config --quiet
```

---

## 实装状态

| Task | 内容 | 状态 |
|------|------|------|
| Task 1 | rule-app 加 opentelemetry 依赖 | 待实装 |
| Task 2 | application.yml OTLP 配置 | 待实装 |
| Task 3 | logback-spring.xml OTLP appender | 待实装 |
| Task 4 | docker-compose 加 otel-lgtm | 待实装 |
| Task 5 | 本地冒烟验证 | 待实装 |
