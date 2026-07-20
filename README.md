# Rule Engine — 通用规则引擎

把「在什么条件下、对谁、产出什么决策」从业务代码里抽出来，做成独立引擎。运营通过可视化界面配置和发布规则，开发不再为每个新场景重复写调度、求值、落库、审计代码。

**纯决策引擎**：只出 Decision（finalDecision + hitDecisions），不执行动作。「命中后做什么」交给消费方或流程引擎。

---

## 一、一句话说清楚能做什么

| 场景 | 怎么用 |
|------|--------|
| **运营活动** | 配置「新用户首单满 50 减 10」→ 业务方调 PULL 接口拿决策，自己发券 |
| **风控反欺诈** | 评分卡 + 决策树组合，实时 PUSH 评估 → 命中高风险决策 → 下游拦截 |
| **营销 AB 实验** | 灰度配置 + 互斥分桶，同一个用户进 A 组/不进 B 组 |
| **多租户 SaaS** | 每个租户独立 Scene/规则/Metric，互不干扰，一份部署服务 N 个业务线 |
| **嵌入式 SDK** | 规则随业务代码打包，零网络跳转本地求值，毫秒级延迟 |

**一句话**：画布上搭规则 → 发布 → 业务调接口拿决策，不用写 if-else。

---

## 二、核心能力矩阵

### 2.1 五种规则形态，全实装

| 形态 | 适合什么 | 怎么理解 |
|------|---------|---------|
| **布尔树** | 多条件组合判定 | 画一棵 And/Or/Not/Xor 树，叶子是 `金额 > 1000` 这种条件 |
| **评分卡** | 风险评分、信用评级 | 每个条件给权重，总分超阈值触发 |
| **决策树** | 分类分流 | if 条件 A → then 结果 1，else if 条件 B → ... |
| **决策表** | 规则矩阵 | 输入列 + 输出列 + 行，像 Excel 一样填 |
| **脚本规则** | 复杂表达式 | 写 `price * quantity > 1000 && userLevel == 'VIP'`，六种引擎可选 |
| **决策图** | 多步编排 | 决策 A → 按结果分支 → 决策 B → 输出，图只编排、叶子引用上面五种 |

### 2.2 六种表达式引擎，按需切换

脚本规则支持六种引擎，发布时选语言即可，引擎自动路由：

| 引擎 | 适合场景 | 特点 |
|------|---------|------|
| **CEL**（Google） | 默认首选 | 类型安全，语法受限不会写坏 |
| **Aviator** | 高性能 | JVM 动态编译，弱类型 |
| **QLExpress** | 阿里生态 | 弱类型，阿里内部大量使用 |
| **Groovy** | 复杂脚本 | 完整 JVM 语言，sandbox 沙箱限制 |
| **JEXL**（Apache） | Java 风格表达式 | 弱类型，permission 限制 |
| **JsonLogic** | 纯数据驱动 | JSON 表达规则，无代码执行，最安全 |

开放扩展：实现 `ExpressionEngine` SPI 即可接入新引擎，不改主干代码。

### 2.3 三种执行策略

| 策略 | 语义 |
|------|------|
| **最高优先级** | 命中多条规则时取 priority 最高的那条决策 |
| **全命中** | 所有命中规则的决策都返回，一条不落 |
| **首命中** | 按 priority 排序，第一条命中就短路返回 |

### 2.4 四种取数方式

| 取数方式 | 说明 | v1 状态 |
|---------|------|---------|
| **属性取数** | 从主体属性表读值（userLevel、regDays...） | 已实装 |
| **SQL 聚合** | 执行聚合 SQL（近 30 天交易额、昨日登录次数...） | 已实装 |
| **外部 HTTP** | 声明式连接器，调外部服务取数（征信分、设备指纹...） | 已实装 |
| **流式** | Flink/Kafka 预聚合结果 | v1 占位，v2 接入 |

### 2.5 配置管理闭环

- **草稿即冻结**：创建/编辑时即跑完整校验 + 快照，不过即拒，保证「预览 = 发布」
- **版本化**：每次发布生成不可变快照，支持回滚（newVersion 基于旧版本重建）
- **灰度发布**：ROLLOUT 百分比 + AB 实验互斥分桶
- **导出/导入**：Bundle 文件跨环境迁移
- **热加载**：发布后毫秒级生效（单服务模式）/ 15s 轮询（SDK 模式）

### 2.6 治理能力

- **静态分析**：7 类规则集冲突检测（死规则、冲突规则、冗余条件…）
- **双向血缘**：规则 ↔ 指标 ↔ 决策，改一个可见影响面
- **全配置审计**：谁在什么时候改了哪条规则的什么字段，有 diff
- **历史重放**：拿历史评估 session，忠实重放看当时怎么决策的
- **决策效果闭环**：标签回灌 → 按规则算 precision/recall

### 2.7 可观测性

- **业务指标**：Prometheus 9 个核心指标（评估量、延迟分布、命中率、取数延迟...）
- **基础设施**：OTLP 三信号推送到 LGTM（Grafana/Loki/Tempo/Mimir）
- **全节点 trace**：每次评估每棵树每个节点的取值和判断结果都可追溯
- **故障降级**：单节点取数失败 → 该条件判定 false，不影响其他规则

---

## 三、怎么跑起来

### 3.1 环境要求

- Java 25
- Maven 3.9+
- MySQL 8.0+（需要 `rule_engine` 库）
- Redis（可选，幂等上半层）

### 3.2 三步启动

```bash
# 1. 初始化数据库（首次）
mysql -u root -e "CREATE DATABASE IF NOT EXISTS rule_engine DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci"
# 迁移脚本在 rule-app 启动时自动执行

# 2. 编译打包
mvn clean package -DskipTests

# 3. 启动服务
java -jar rule-app/target/rule-app-*.jar
# 服务启动在 localhost:8080
```

### 3.3 第一条规则（5 分钟体验）

```bash
# 1. 创建 Tenant
curl -X POST http://localhost:8080/admin/v1/tenants \
  -H 'Content-Type: application/json' \
  -d '{"code":"demo","name":"演示租户"}'

# 2. 创建 Scene
curl -X POST http://localhost:8080/admin/v1/scenes \
  -H 'Content-Type: application/json' \
  -d '{"tenantCode":"demo","code":"promo","name":"营销活动","mode":"PULL"}'

# 3. 创建 Decision
curl -X POST http://localhost:8080/admin/v1/decisions \
  -H 'Content-Type: application/json' \
  -d '{"tenantCode":"demo","code":"SEND_COUPON","name":"发券","priority":10}'

# 4. 创建规则（满 100 减 10）
curl -X POST http://localhost:8080/admin/v1/rules \
  -H 'Content-Type: application/json' \
  -d '{
    "tenantCode":"demo","sceneCode":"promo","name":"首单满减",
    "kind":"AST_BOOLEAN",
    "conditions":[{"conditionType":"GT","metricCode":"orderAmount","params":{"threshold":100}}],
    "decisionBindings":[{"decisionCode":"SEND_COUPON"}]
  }'

# 5. 发布
curl -X POST http://localhost:8080/admin/v1/rules/publish \
  -H 'Content-Type: application/json' \
  -d '{"ruleId":"<上一步返回的 ruleId>"}'

# 6. 评估
curl -X POST http://localhost:8080/api/v1/rule/evaluate \
  -H 'Content-Type: application/json' \
  -d '{"tenantCode":"demo","sceneCode":"promo","subjectType":"USER","subjectId":"u1","providedMetrics":{"orderAmount":150}}'

# 返回：{"finalDecision":{"code":"SEND_COUPON",...},"hitDecisions":[...],"satisfied":true}
```

### 3.4 模块导航

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

## 四、横向对比

跟业界常见规则引擎/方案放在一起看，明确这个项目的定位差异。

> **关于 GoRules ZEN**：ZEN Engine 是 Rust 写的开源 BRE 库（跨语言嵌入 + JDM 决策图），GoRules 另有一套闭源商业 BRMS（多租户/版本/治理/管理台）。本项目对标的是**商业 BRMS 的产品能力**，在「开源引擎库」这层之上自建了平台层。开源 ZEN 对标的是 `rule-kernel` 一个模块，不是整个平台。

| 维度 | Rule Engine（本项目） | GoRules ZEN / BRMS | Drools | EasyRules | 业务自研 if-else |
|------|----------------------|---------------------|--------|-----------|-----------------|
| **定位** | 通用产品，多租户多 Scene | ZEN：可嵌入 BRE 库。BRMS（闭源）：规则管理平台 | 通用规则引擎 | 轻量规则框架 | 一次性业务代码 |
| **规则建模** | 6 种（布尔树+评分卡+决策树+决策表+脚本+决策图 DECISION_FLOW） | JDM 有向图（Decision Table/Switch/Function/Expression/Decision 5 节点），**全图建模** | 主要为 DRL 文本规则 + 决策表 | 主要为注解/DSL 简单规则 | 无固定形态 |
| **编排能力** | DECISION_FLOW（两层：图编排 + 5 形态叶子引用） | 原生图编排 + 子决策递归调用（**无叶子/编排分层**） | Ruleflow Group + Agenda | 组合规则 | 手工 |
| **表达式引擎** | 6 种可插拔（CEL/Aviator/QLExpress/JsonLogic/JEXL/Groovy） | 1 种 ZEN EL（带 intellisense/NL/类型推断） | MVEL / FEEL | MVEL / SpEL | 硬编码 |
| **配置界面** | 内置 REST API + 前端管理台（React） | ZEN：无。BRMS：Web 管理台 + JDM Editor（画布） | Business Central Workbench | 无内置 UI | 无 |
| **多租户** | 原生支持（tenant 隔离贯穿全链路） | ZEN：无。BRMS：有 | 需自行实现 | 不支持 | 需自行实现 |
| **版本化+灰度** | 内置（快照+回滚+ROLLOUT+AB 桶） | ZEN：无。BRMS：有 | 部分支持 | 不支持 | 无 |
| **嵌入式 SDK** | 支持（零 Spring，四种规则来源模式） | ZEN：**原生跨语言嵌入**（Rust→Node/Py/Go/C，进程内微秒级）。BRMS：远程调用 | KIE Server 远程调用 | 原生嵌入 | 直接写代码 |
| **取数抽象** | 4 种（属性/SQL/HTTP/流式），统一 Metric SPI，**引擎自取数** | ZEN：无（调用方组装全量 context JSON）。BRMS：有 | 事实对象插入 | 事实对象 | 手工查库 |
| **血缘+治理** | 内置：静态分析+血缘+审计+重放+效果闭环 | ZEN：无。BRMS：有 | 部分（Drools Verifier） | 无 | 无 |
| **可观测性** | OTLP + Prometheus + LGTM 开箱即用 | ZEN：无。BRMS：有 | 需自行集成 | 无 | 需自行集成 |
| **性能** | JVM，服务/SDK 双模，毫秒级 | **Rust+LTO，进程内微秒级**，可塞进 Lambda | JVM，较重 | JVM，轻量 | 原生，但无管理 |
| **上手成本** | 中等（需理解 Scene/Rule/Metric 模型），有管理台 | ZEN：低（库，几行代码）。BRMS：中等（JDM 模型） | 高（DRL 语法+KIE API+复杂部署） | 低（几个注解搞定） | 低（但越写越乱） |
| **适合规模** | 中大型：多业务线、规则频繁变更、需治理 | ZEN：小型嵌入。BRMS：中大型 | 大型：复杂推理、CEP 事件流 | 小型：几十条简单规则 | 临时：3-5 个 if |

### 为什么不用 Drools

- Drools 的 DRL 语法学习曲线陡，运营不可能上手
- KIE 体系重，部署和运维成本高
- 多租户、灰度、热加载、血缘治理这些自己补工作量大
- 本项目定位是「运营可自助配置的决策平台」，不是「开发写规则的推理引擎」

### 为什么不用 EasyRules

- 太轻：没有版本、没有快照、没有灰度、没有 UI
- 规则一多（>100 条）没有治理手段
- 取数靠事实对象注入，没有统一的 Metric 抽象
- 适用场景是「代码里加几条简单规则」，不是「运营管理数百条规则的业务线」

### 为什么不直接用 GoRules ZEN Engine

- ZEN Engine 开源的是**可嵌入 BRE 库**（Rust core + Node/Python/Go 绑定），不是规则管理平台——没有多租户、没有版本灰度、没有取数抽象、没有治理血缘、没有审计、没有管理台
- 这些能力在 GoRules 是**闭源商业 BRMS**，本项目的目标恰好是把这层做出来并开源
- ZEN 的 JDM 全图建模对单次决策表达力很强，但本项目需要的「运营自助配规则 + 批量治理 + 取数一等公民」在纯图模型里反而别扭——所以做了 DECISION_FLOW 两层设计（图编排 + 叶子引用），既吸收图编排的优点，又保留表单编辑和 Metric SPI 的运营友好度
- ZEN 的跨语言嵌入（Rust→Node/Py/Go）是性能甜区，本项目 JVM only；如果你需要进程内嵌入 Python/Node 服务，ZEN 更合适；如果你需要的是一个带管理台的规则**平台**，本项目更合适

### 什么时候直接用 if-else

- 总共就 3-5 个条件，半年不改一次
- 没有运营自助配置的需求
- 不需要版本/灰度/审计/血缘

---

## 五、技术栈速览

| 层面 | 选型 |
|------|------|
| 语言 | Java 25 |
| 框架 | Spring Boot 4.1 + Spring Modulith 2.1 |
| ORM | MyBatis-Plus 3.5 |
| DB | MySQL 8.0（v1 全 MySQL） |
| 缓存 | Redis + Caffeine |
| 调度 | XXL-JOB 3.4（多实例）/ ThreadPoolTaskScheduler（单机） |
| 表达式 | CEL / Aviator / QLExpress / JsonLogic / JEXL / Groovy（sandbox） |
| 正则 | RE2J（线性时间，防 ReDoS） |
| 前端 | React 18 + Ant Design 5 + Vite + Zustand |
| 可观测 | Micrometer + Prometheus + OTLP + LGTM |
| 测试 | ArchUnit + Testcontainers + JMH + WireMock |

---

## 六、下一步看什么

- **想理解设计决策** → `docs/00-decisions.md`（为什么这么做，30+ 条决策）
- **想加能力** → `docs/04-extension.md`（加条件类型、取数源、表达式引擎）
- **想调接口** → `docs/10-api-contract.md`
- **想看路线图** → `docs/ROADMAP.md`
- **想看例子** → `docs/examples/`
