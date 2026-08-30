# Rule Engine — 通用规则引擎

把「在什么条件下、对谁、产出什么决策」从业务代码里抽出来，做成独立引擎。运营通过可视化界面配置和发布规则，开发不再为每个新场景重复写调度、求值、落库、审计代码。

**纯决策引擎**：只出 Decision（finalDecision + hitDecisions），不执行动作。「命中后做什么」交给消费方或流程引擎。

> **安全边界**：管理接口本身不负责身份认证，`X-Actor-Id` / `X-Actor-Type` 必须由可信网关注入。请勿把管理端口直接暴露到公网，详见 [安全策略](SECURITY.md)。

---

## 快速理解

它把反复变化的业务判断配置成规则，由业务系统在需要时请求一个 `Decision`：

```text
配置端：Tenant → Scene → Decision / Rule → 发布
业务侧：业务事件 → 评估接口或 SDK → Decision → 业务系统执行发券、拦截等动作
```

例如，运营配置“订单金额不少于 100 返回 `SEND_COUPON`”；订单服务提交 `orderAmount` 后得到该决策，再自行发券。规则引擎不持有券、不调用业务动作，也不替代流程引擎。

## 适用边界

适合规则需要由运营或风控持续调整、需要版本快照/灰度/审计、多个业务线共享规则平台的场景。支持布尔树、评分卡、决策树、决策表、脚本规则和决策图；支持属性、SQL 聚合和声明式 HTTP 取数。

如果只有少量长期不变的条件，直接写 `if-else` 通常更简单。需要对 Drools、Easy Rules、GoRules ZEN 等方案做选型时，请看[业界对比与选型说明](docs/reference-projects.md#产品级选型对比)。

---

## 快速开始

### 环境要求

- Java 25
- Maven 3.9+
- Docker（真实 MySQL 集成测试和本地 Compose 环境需要）
- MySQL 8.0+（默认运行、完整业务体验和生产部署统一使用）
- Bash、curl、jq（运行下方接口示例需要）

### 构建与启动

在仓库根目录执行完整后端验证；数据库测试会自行创建临时 MySQL，不使用已有业务数据库：

```bash
docker info
mvn --batch-mode --no-transfer-progress clean verify -Pexamples \
  '-Dsurefire.includes=**/Test*.java,**/*Test.java,**/*Tests.java,**/*TestCase.java,**/*IT.java'
```

测试报告核验及前端构建命令见 [贡献指南](CONTRIBUTING.md)。

默认配置使用 MySQL，可通过 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD` 覆盖连接信息，**无需设置 profile**。首次启动会对目标空库执行 Flyway 基线；请先准备独立的 MySQL 环境。

在全新本地环境中，可通过 Compose 启动 MySQL、应用和可观测套件：

```bash
# 仅在尚无 .env 时复制，避免覆盖已有配置
if [ ! -e .env ]; then cp .env.example .env; fi
# 修改 .env 中的所有密码后启动
docker compose up --build
```

Compose 使用独立的 `mysql-data` 卷，服务端口只绑定 `127.0.0.1`。若 3306、8080、3000、4317 或 4318 已被占用，请先规划独立端口，不要停止或清空已有数据库。不要把合并后的 V1 迁移直接指向已有业务库；旧库的 Flyway 历史切换需另行核对。

已有**专门新建的空 MySQL 数据库**时，也可设置以下环境变量后直接启动 jar：

```bash
export SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/rule_engine_demo?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
export SPRING_DATASOURCE_USERNAME='rule_engine_demo'
export SPRING_DATASOURCE_PASSWORD='<该空库用户的密码>'
java -jar rule-app/target/rule-app-*.jar
```

以上方式二选一。启动日志出现 `Started RuleEngineApplication` 后，访问 `http://localhost:8080/actuator/health` 检查健康状态（正常应为 `UP`），也可通过 `/admin/v1/tenants` 验证数据库读取。

Redis 健康检查默认关闭，数据库等其他健康检查保持启用。下方 payload 规则不需要 Redis；部署使用 `STREAM` 取数时，必须配置实际 Redis 连接并设置 `MANAGEMENT_HEALTH_REDIS_ENABLED=true`，Redis 不可用时整体健康检查将返回 `DOWN`。该开关只控制健康检查，不禁用 Redis 客户端或取数逻辑。直接运行 jar 可用环境变量开启；Compose 部署则需将该变量传入 `rule-app` 容器，仅写入 `.env` 不会自动传入。

Compose 仅用于本地开发；生产环境应使用 `mysql` profile、外部密钥管理和可信鉴权网关。`mysql` profile 强制要求显式提供上述三项数据源环境变量，不使用开发默认连接信息。

### 第一条规则（MySQL）

示例规则为「订单金额大于等于 100 时返回 `SEND_COUPON` 决策」。它不判断是否首单，也不实际发券或扣减金额。订单金额直接来自事件 `payload`，无需预建 Metric。

确认连接的是上一步的独立演示环境后，在 Bash 中复制执行整个代码块。每次创建一个新租户；管理请求中的 actor 头仅用于本地演示，生产环境必须由可信网关注入。

```bash
# 子 shell 遇到 HTTP 错误或断言失败立即退出
(
set -euo pipefail
BASE_URL="${BASE_URL:-http://localhost:8080}"
TENANT_CODE="quickstart_$(date +%s)"

# 1. 创建 Tenant
TENANT_ID=$(curl -fsS -X POST \
  "$BASE_URL/admin/v1/tenants?code=$TENANT_CODE&name=Quickstart" \
  -H 'X-Actor-Id: quickstart' | jq -er '.data')

# 2. 创建 Scene，并声明订单金额字段
curl -fsS -X POST "$BASE_URL/admin/v1/scenes" \
  -H 'Content-Type: application/json' -H 'X-Actor-Id: quickstart' \
  -d '{"tenantId":'"$TENANT_ID"',"sceneCode":"promo","name":"营销活动",
       "dominantMode":"PULL","subjectType":"USER","eventTypes":["ORDER"],
       "payloadSchema":[{"name":"orderAmount","type":"NUMBER","required":true}],
       "defaultParams":{}}' | jq -e '.success == true'

# 3. 创建 Decision
curl -fsS -X POST "$BASE_URL/admin/v1/decisions?tenantId=$TENANT_ID" \
  -H 'Content-Type: application/json' -H 'X-Actor-Id: quickstart' \
  -d '{"code":"SEND_COUPON","name":"建议发券","priority":10}' \
  | jq -e '.success == true'

# 4. 创建规则草稿，保存返回的规则定义 ID
RULE_ID=$(curl -fsS -X POST "$BASE_URL/admin/v1/rules" \
  -H 'Content-Type: application/json' -H 'X-Actor-Id: quickstart' \
  -d '{
    "tenantId":'"$TENANT_ID"',"sceneCode":"promo","code":"order-coupon",
    "name":"订单满额发券建议","kind":"AST_BOOLEAN",
    "body":{"type":"AstBody","conditionAst":{"type":"AndNode","children":[
      {"type":"ConditionNode","conditionType":"GTE","valueRef":"PAYLOAD",
       "metricCode":"orderAmount","params":{"threshold":100}}
    ]}},
    "decisionBindings":[{"decisionCode":"SEND_COUPON"}],
    "preGates":[],"triggerEventTypes":["ORDER"]
  }' | jq -er '.data.ruleDefinitionId')

# 5. 发布
curl -fsS -X POST "$BASE_URL/admin/v1/rules/$RULE_ID/publish?tenantId=$TENANT_ID" \
  -H 'X-Actor-Id: quickstart' | jq -e '.success == true'

# 6. 金额恰好 100：命中，输出 SEND_COUPON
curl -fsS -X POST "$BASE_URL/api/v1/rule/evaluate" \
  -H 'Content-Type: application/json' \
  -d '{"tenantCode":"'"$TENANT_CODE"'","sceneCode":"promo","eventType":"ORDER",
       "subjectId":"u1","eventId":"order-100","payload":{"orderAmount":100}}' \
  | jq -e '.data | select(.ruleHit == true and .finalDecision.code == "SEND_COUPON" and .errorCode == null)'

# 7. 金额 99：未命中且没有错误
curl -fsS -X POST "$BASE_URL/api/v1/rule/evaluate" \
  -H 'Content-Type: application/json' \
  -d '{"tenantCode":"'"$TENANT_CODE"'","sceneCode":"promo","eventType":"ORDER",
       "subjectId":"u1","eventId":"order-99","payload":{"orderAmount":99}}' \
  | jq -e '.data | select(.ruleHit == false and .finalDecision == null and .errorCode == null)'
)
```

HTTP 成功响应统一包装为 `{"success":true,"data":...}`，上面的评估命令只输出 `data`。演示配置和审计数据会保留在演示库中，重复执行会新增租户，不会自动清理已有数据。

## 模块导航

```
rule-engine/
├── docs/                          # 所有设计文档（从这里开始读）
│   ├── 00-decisions.md            #   决策日志（30+ 核心决策，了解「为什么这么做」）
│   ├── 01-concepts.md             #   概念词典
│   ├── 02-runtime.md              #   运行时全链路
│   ├── 04-extension.md            #   扩展指南（加条件类型/取数源/表达式引擎）
│   ├── 05-storage.md              #   存储模型 + DDL
│   ├── 09-skeleton.md             #   工程骨架
│   ├── 10-api-contract.md         #   API 契约
│   ├── ROADMAP.md                 #   产品路线图
│   └── examples/                  #   端到端 curl 剧本
├── rule-kernel/                   # 内核：零 Spring，纯 Java SPI + AST + 求值引擎
├── rule-config-svc/               # 配置写服务：规则/Scene/Metric/Decision CRUD + 发布
├── rule-eval-svc/                 # 评估服务：PUSH/PULL/dry-run + 取数编排 + 落库
├── rule-api/                      # HTTP 层：三级前缀 /admin·api·sdk/v1
├── rule-app/                      # 启动类 + 模块装配
├── rule-sdk/                      # 嵌入式 SDK（零 Spring 依赖）
├── rule-sdk-spring-boot-starter/  # SDK 的 Spring Boot 自动装配
├── rule-expression/               # 六种表达式引擎（每种一个子模块 + starter）
├── rule-audit-svc/                # 审计服务
├── rule-observability/            # 可观测性（trace/prometheus/OTLP）
├── rule-job-svc/ / rule-job-xxl/  # 定时调度（单机 + XXL-JOB 多实例）
├── rule-benchmark/                # JMH 性能基准
└── rule-samples/                  # 样例
```

**改代码前先看**：`docs/09-skeleton.md`（模块边界）→ `docs/04-extension.md`（扩展点在哪）→ 对应模块代码。

---

## 技术栈速览

| 层面 | 选型 |
|------|------|
| 语言 | Java 25 |
| 框架 | Spring Boot 4.1 + Spring Modulith 2.1 |
| ORM | MyBatis-Plus 3.5 |
| DB | MySQL 8.0+（默认运行与生产部署统一） |
| 缓存 | Redis + Caffeine |
| 调度 | XXL-JOB 3.4（多实例）/ ThreadPoolTaskScheduler（单机） |
| 表达式 | CEL / Aviator / QLExpress / JsonLogic / JEXL / Groovy（sandbox） |
| 正则 | RE2J（线性时间，防 ReDoS） |
| 前端 | React 18 + Ant Design 5 + Vite + Zustand |
| 可观测 | Micrometer + Prometheus + OTLP + LGTM |
| 测试 | ArchUnit + Testcontainers + JMH + WireMock |

---

## 下一步看什么

- **想理解设计决策** → `docs/00-decisions.md`（为什么这么做，30+ 条决策）
- **想加能力** → `docs/04-extension.md`（加条件类型、取数源、表达式引擎）
- **想调接口** → `docs/10-api-contract.md`
- **想看路线图** → `docs/ROADMAP.md`
- **想看例子** → `docs/examples/`
- **想做技术选型** → `docs/reference-projects.md`

---

## 参与贡献与许可证

- 提交代码前请阅读 [贡献指南](CONTRIBUTING.md) 和 [行为准则](CODE_OF_CONDUCT.md)。
- 安全问题请按 [安全策略](SECURITY.md) 私密报告，不要提交公开 Issue。
- 对外变更记录见 [CHANGELOG](CHANGELOG.md)。
- 本项目采用 [GNU General Public License v3.0](LICENSE)（GPL-3.0-only）发布。
