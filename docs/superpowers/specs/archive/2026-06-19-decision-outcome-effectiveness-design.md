# B32 决策效果闭环 / 规则有效性度量 — 设计

来源 backlog B32 / `docs/08-evolution.md` §2.27。本文档定稿后转实现计划。

## 1. 问题与价值门控

引擎当前每次决策落 `evaluation_session`（D21），可观测体系（§2.6）度量的是**系统级**指标（QPS / 延迟 / 命中率）。**无业务效果度量**——"判 HIGH 的交易事后是不是真欺诈"答不了，单条规则的查准 / 查全无从谈起。

B32 要补的是：业务侧把决策的**真实结局**（fraud / not、转化 / 未转化）回灌，按规则 / Decision 维度聚合 TP/FP/FN → precision / recall，并按时间窗看漂移。

**价值门控（必须明示）**：核心价值依赖**业务侧真实标签**回灌。开发期无生产标签，本轮只能建好「接入位 + 聚合骨架」并验「回灌落库 + 聚合 SQL 正确」（用构造标签数据），**验不到真实风控效果**。这是 backlog 已认的范围限定。

## 2. 架构定位：给已有决策日志补"真值反馈环"

对照成熟做法（ML 可观测平台 Evidently / Fiddler / Databricks，欺诈风控反馈环 Sardine）——它们都是同一范式：

1. **决策 / 预测日志**（捕获每次决策 + join key + 时间）
2. **延迟真值标签回灌**（按 join key 关联，晚到是常态，称 partial feedback）
3. **按时间窗 join 聚合** precision/recall，标签回填后重算

**关键事实**：第 1 层我们已经有了——`evaluation_session` 的 `hit_decisions` JSON 每条已携带 `{code, category, ruleVersionId}`，规则级 + 决策级归因现成（不必 join 脆弱的 `node_trace`）。所以 B32 **不是新塞一张孤立表打补丁，而是给已有决策日志补上反馈环的另一半（第 2、3 层）**。

**reject inference 诚实纪律**（欺诈领域固有）：被 `BLOCKED` / 未命中放行的事件，业务侧未必能给出"本应是什么"的真值；precision 可算，recall 天生残缺。引擎**只提供接入位 + 聚合并诚实回报残缺面**（unlabeled 数 / blocked 数），不替业务做真值推断——守 §2.27"做决策不做业务判定"线。

## 3. 模块落点（顺既有 CQRS 边界）

决策日志在本仓已是 CQRS 切分：**eval-svc 写** `evaluation_session`，**audit-svc 读**（`rule-audit-svc` 纯读投影 `EvalSessionReadMapper` 等，零写）。`decision_outcome` 顺这条边界落：

| 组件 | 落点模块 | 包 | 一致性 |
|---|---|---|---|
| `decision_outcome` 表 DDL | 集中 migration（`rule-config-svc/.../db/migration/V1_36`） | — | — |
| 标签回灌写（实体 + Mapper + WriteService） | **rule-eval-svc** | `internal/outcome/` | **同步强一致事务**——业务回灌须拿到落库确认（区别于评估期 C 类 async best-effort，是有意的不同类别） |
| 效果聚合查询（读投影 + Mapper + EffectivenessService） | **rule-audit-svc** | `audit/internal` + `audit/api` | **按需 SQL 聚合**（冷路径强一致，沿用 B33 血缘"按需扫、零常驻索引"定式） |
| HTTP 入口（POST 回灌 / GET 报表） | **rule-api** | `web/admin/OutcomeController` | — |

把写放 eval-svc（它owns 决策日志写模型）、读放 audit-svc（它owns 决策日志读模型），是**顺既有边界扩展**；若全塞 audit-svc 会破坏其纯读特性，全塞 eval-svc 会让评估模块长出查询报表职责。两模块各自持有自己的 row 类型（不跨模块共享实体），与现有 `EvalSessionRow` 投影一致。

## 4. 数据模型

`decision_outcome`（V1_36，`utf8mb4` / InnoDB）：

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | BIGINT AUTO_INCREMENT PK | |
| `tenant_id` | BIGINT NOT NULL | |
| `event_id` | VARCHAR(128) NOT NULL | join key → `evaluation_session.uk_tenant_event` |
| `outcome_label` | VARCHAR(64) NOT NULL | **业务自定义串**，引擎不解释（如 `FRAUD`/`NOT_FRAUD`/`CONVERTED`） |
| `outcome_value` | DECIMAL(18,4) NULL | 可选数值（如真实损失额），聚合可选维度 |
| `outcome_note` | VARCHAR(512) NULL | 可选备注 |
| `labeled_at` | TIMESTAMP(3) NOT NULL | 业务真值确定时刻（标签时刻，非回灌落库时刻） |
| `source` | VARCHAR(64) NULL | 回灌方标识 |
| `created_at` | TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) | |
| `updated_at` | TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) | |

约束 / 索引：
- `UNIQUE KEY uk_tenant_event (tenant_id, event_id)`——一个 event 一条 outcome，重复回灌**覆盖更新**（标签可能修正）。
- `KEY idx_tenant_labeled (tenant_id, labeled_at)`。
- **不设外键、不校验对应 session 已存在**——标签可能早于 session 到达（async 落库延迟）甚至 session best-effort 丢失（partial feedback）；标签无条件落库，存在性在聚合期 join 处理。

`outcome_label` 取值开放（业务定义）故用 VARCHAR 而非 enum——这是"集合开放可扩展"的合规例外（对比封闭取值用 enum 的纪律）。

## 5. 回灌 API（写侧，同步）

```
POST /admin/v1/decision-outcomes
Body: { "tenantId": 1, "outcomes": [
  { "eventId": "evt-001", "outcomeLabel": "FRAUD", "outcomeValue": 1280.50,
    "labeledAt": "2026-06-18T10:00:00Z", "source": "fraud-ops", "note": "chargeback" },
  ... ] }
```

- 单条 + 批量统一走 `outcomes` 列表（单条 = size 1）。
- 幂等：按 `(tenantId, eventId)` upsert（`INSERT ... ON DUPLICATE KEY UPDATE` 覆盖 label/value/note/labeledAt/source + 刷 updated_at）。
- 返回 `{ accepted: N }`（落库条数）。
- typed 请求 DTO → eval-svc typed 入参（`OutcomeRecord` record），不经 JSON String 来回；DTO ↔ 入参极少字段一次性映射可手写，否则走 MapStruct（`web/.../convert`）。
- `labeledAt` 为 `Instant`（ISO-8601），落库转 `LocalDateTime`（与既有 `occurredAt` 同口径）。

## 6. 效果聚合 API（读侧，按需 SQL）

```
GET /admin/v1/decision-outcomes/effectiveness
  ?tenantId=1&sceneCode=fraud_check
  &from=2026-06-01T00:00:00Z&to=2026-06-19T00:00:00Z
  &positiveLabels=FRAUD,CONFIRMED_FRAUD
  &dimension=RULE_VERSION            # RULE_VERSION | DECISION
  &bucket=NONE                       # NONE | DAY | WEEK（漂移时间序列）
```

**口径**（守 reject-inference 诚实）：positive 判定由查询期 `positiveLabels` 给（引擎不解释标签语义）。对维度 K（某 ruleVersionId 或某 decisionCode）、scene 内、时间窗内**有标签**的 session：

| | label ∈ positiveLabels | label ∉ positiveLabels |
|---|---|---|
| K 命中（K ∈ hit_decisions） | TP | FP |
| K 未命中 | FN | TN |

- `precision = TP / (TP + FP)`，`recall = TP / (TP + FN)`，`fireRate = 命中数 / 窗内总数`。
- **recall 的 scene 作用域**：FN 分母 = 该 scene 内 positive-labeled 且 K 未命中的 session（`sceneCode` 为查询参数，避免跨模块查 rule→scene 映射，保持 audit-svc 本地）。
- **诚实回报**：每行附 `labeledCount` / `unlabeledCount` / `blockedCount`——unlabeled 不计入任何 TP/FP/FN 分母（沿用 BLOCKED/ERROR 不计命中率分母口径）；blocked 数显式暴露 reject-inference 残缺面。
- `bucket=DAY|WEEK` 时按 `occurred_at`（决策发生时间，非标签到达时间）分桶出时间序列 = **漂移视图**；标签回填后重查即反映，无需重算物化。

**SQL 实现**：`evaluation_session` 的 `hit_decisions` JSON 数组经 MySQL `JSON_TABLE` 展开为 `(ruleVersionId, decisionCode)` 行，LEFT JOIN `decision_outcome`（按 tenant+event），按维度 + 桶 `GROUP BY` 聚合 `CASE WHEN` 计数。走 `@Select` Mapper（动态 / 原生 SQL，不引 JdbcTemplate）。规则版本 → 逻辑 code/name 富化在 API 层做，不在 SQL 跨模块 join。

## 7. 测试策略

- **eval-svc 写侧**：Mapper upsert（首插 / 覆盖更新 / 缺 session 也落库）；WriteService 批量幂等。
- **audit-svc 读侧**：构造若干 `evaluation_session`（含 `hit_decisions` 各种命中组合）+ `decision_outcome`，断言聚合 SQL 的 TP/FP/FN/precision/recall/fireRate 算对；含 unlabeled / blocked 不入分母、桶时间序列、positiveLabels 多值。
- **API 层**：回灌 201/200 + 聚合响应形状。
- 每模块全量测试绿后 commit；跨模块改动带 `-am`，最后 `clean test` 兜底。
- **真实服务 e2e**（涉落库链路，必做）：起服务 → 构造 session（评估或直接造数据）+ 回灌标签 → 查持久层确认 `decision_outcome` 真落库 → 调聚合 API 核对 TP/FP/precision/recall 与手算一致 → 清理测试数据。

## 8. 范围边界（YAGNI）

**做**：`decision_outcome` 表 + 同步回灌 API + 按需 SQL 聚合（TP/FP/FN/precision/recall/fireRate + DAY/WEEK 漂移序列）+ reject-inference 诚实回报。

**不做（明确推迟，叠加式演进，不返工）**：
- 物化 rollup 表 / 批 job 聚合（v1 按需 SQL 足够；规模化时叠加）。
- §2.16 A/B、§2.25 陪跑衔接（表结构保持通用，不写专门字段 / 逻辑）。
- 特征漂移 / PSI / KS（那是 §2.6 系统监控 + 模型节点territory，非业务效果）。
- 前端报表 UI（后续，本轮只到 API）。
- `outcome_label` 语义校验 / 封闭枚举（业务定义，引擎不解释）。

## 9. 文档同步

落地后 `docs/08-evolution.md` §2.27 标注实装 + 落点；`docs/10-api-contract.md` 补两个 admin 端点；backlog B32 移入已落地清单。
